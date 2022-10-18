package com.github.suhli.ideagokratosplugin.helper

import com.github.suhli.ideagokratosplugin.extends.KratosTask
import com.github.suhli.ideagokratosplugin.pb.KratosPbAction
import com.github.suhli.ideagokratosplugin.pb.KratosPbClientAction
import com.goide.sdk.GoPackageUtil
import com.goide.sdk.GoSdkUtil
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.protobuf.ide.settings.PbProjectSettings
import com.intellij.protobuf.jvm.PbJavaOuterClassIndex
import com.intellij.protobuf.lang.PbFileType
import com.intellij.protobuf.lang.psi.PbFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveState
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import java.nio.charset.Charset

private var LOG: Logger? = null
private fun getLogger(): Logger {
    if (LOG == null) {
        LOG = Logger.getInstance("PbHelper")
    }
    return LOG!!
}

private fun findModules(file: PsiFile): HashSet<String> {
    val result = hashSetOf<String>()
    if (file !is PbFile) {
        return result
    }
    val project = file.project
    val moduleComments = file.children.filter { v -> v is PsiComment && v.text.contains("modules:") }
    val settings = PbProjectSettings.getInstance(project)
    val entries = arrayListOf<PbProjectSettings.ImportPathEntry>()
    entries.addAll(settings.importPathEntries)
    val toAddModule = arrayListOf<PbProjectSettings.ImportPathEntry>()
    for(comment in moduleComments){
        val text = comment.text
        val match = Regex("modules:(.+)").find(text)
        val module = match?.groupValues?.find { m -> !m.contains("modules") } ?: ""
        val pkg = GoPackageUtil.findByImportPath(module, project, null, ResolveState.initial())
        if(pkg.isEmpty()) continue
        val location = pkg.first().directories.first().path
        val entry = settings.importPathEntries.find { e ->
            e.location == location
        }
        if(entry == null){
            val e = PbProjectSettings.ImportPathEntry("file://$location","")
            entries.add(e)
            toAddModule.add(e)
        }
        result.add("--proto_path=$location")
    }
    if(toAddModule.isNotEmpty()){
        settings.importPathEntries = entries
    }
    return result
}

private fun findDependency(file: PsiFile): HashSet<String> {
    val result = hashSetOf<String>()
    if (file !is PbFile) {
        return result
    }
    val dependsComments = file.children.filter { v -> v is PsiComment && v.text.contains("depends:") }
    for(comment in dependsComments){
        val text = comment.text
        val match = Regex("depends:(.+)").find(text)
        val path = match?.groupValues?.find { m -> !m.contains("depends") } ?: ""
        if (path.isNotEmpty()) {
            result.add("--proto_path=${path}")
        }
    }
    return result
}

fun genPbTask(file: PsiFile): KratosTask? {
    if (file !is PbFile) {
        return null
    }
    val project = file.project
    val parentPath = DirHelper.relativeToRoot(file.parent ?: return null) ?: return null
    val otherPaths = hashSetOf<String>()
    otherPaths.addAll(findDependency(file))
//    otherPaths.addAll(findModules(file))

    val cmds = arrayListOf("protoc")
    cmds.addAll(otherPaths)
    cmds.add("--proto_path=${project.basePath}/$parentPath")
    cmds.add("--go_out=paths=source_relative:${project.basePath}/$parentPath")
    cmds.add("${project.basePath}/$parentPath/${file.name}")
    val cmd = GeneralCommandLine(cmds)
        .withCharset(Charset.forName("UTF-8"))
        .withWorkDirectory(project.basePath)
    return KratosTask(
        {
            getLogger().info("will run pb command:${cmd.commandLineString}")
            val output = ExecUtil.execAndGetOutput(cmd)
            getLogger().info("pb command code:${output.exitCode} output:${output.stdout} err:${output.stderr}")
        },
        "Generate Client Task"
    )
}

fun genClientTask(file: PsiFile): KratosTask? {
    val project = file.project
    val parentPath = DirHelper.relativeToRoot(file.parent ?: return null) ?: return null
    val otherPaths = hashSetOf<String>()
    otherPaths.addAll(findDependency(file))
    val cmds = arrayListOf("protoc")
    cmds.addAll(otherPaths)
    cmds.add("--proto_path=${project.basePath}/$parentPath")
    cmds.add("--go_out=paths=source_relative:${project.basePath}/$parentPath")
    cmds.add("--go-http_out=paths=source_relative:${project.basePath}/$parentPath")
    cmds.add("--go-grpc_out=paths=source_relative:${project.basePath}/$parentPath")
    cmds.add("${project.basePath}/$parentPath/${file.name}")
    val cmd = GeneralCommandLine(cmds)
        .withCharset(Charset.forName("UTF-8"))
        .withWorkDirectory(project.basePath)
    return KratosTask(
        {
            getLogger().info("will run client command:${cmd.commandLineString}")
            val output = ExecUtil.execAndGetOutput(cmd)
            getLogger().info("client command code:${output.exitCode} output:${output.stdout} err:${output.stderr}")
        },
        "Generate Client Task"
    )
}

fun genAllPb(p: Project): List<KratosTask> {
    val files = FileTypeIndex.getFiles(PbFileType.INSTANCE, GlobalSearchScope.projectScope(p))
    val manager = PsiManager.getInstance(p)
    val tasks = arrayListOf<KratosTask>()
    for (vf in files) {
        val file = manager.findFile(vf) ?: continue
        val clientPbComment =
            file.children.find { v -> v is PsiComment && v.text.contains(KratosPbClientAction.TOKEN) }
        if (clientPbComment != null) {
            getLogger().info("find client comment:${file.virtualFile.path}")
            tasks.add(genClientTask(file) ?: continue)
        }
        val pbComment = file.children.find { v -> v is PsiComment && v.text.contains(KratosPbAction.TOKEN) }
        if (pbComment != null) {
            getLogger().info("find pb comment:${file.virtualFile.path}")
            tasks.add(genPbTask(file) ?: continue)
        }
    }
    PbProjectSettings.notifyUpdated(p)
    return tasks
}