package com.github.suhli.ideagokratosplugin.helper

import com.github.suhli.ideagokratosplugin.GoPluginKratosBundle
import com.github.suhli.ideagokratosplugin.extends.KratosTask
import com.intellij.codeInsight.hint.HintManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.runBackgroundableTask
import com.intellij.openapi.project.Project


fun runKratosTaskInBackground(taskName: String, project: Project, tasks: List<KratosTask>) {
    if (tasks.isEmpty()) {
        return
    }
    val editor = FileEditorManager.getInstance(project).selectedTextEditor


    runBackgroundableTask(taskName, project, true) {
        it.isIndeterminate = false
        val size = tasks.size
        it.fraction = 0.0
        for ((i, t) in tasks.withIndex()) {
            if (t.needWrite) {
                WriteCommandAction.runWriteCommandAction(project, t.runnable)
            } else {
                t.runnable.run()
            }
            it.fraction = ((i + 1) / size).toDouble()
        }
        it.fraction = 1.0
        if (editor != null) {
            Notifications.Bus.notify(Notification("com.github.suhli.ideagokratosplugin", "$taskName done!", NotificationType.INFORMATION), project)
        }
    }
}