package com.dud.embydownload.ui

import com.dud.embydownload.EmbyApplication
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.Window

enum class DialogType {
    CONFIRM, INFO, ERROR
}

data class ConfirmWithOptionResult(
    val confirmed: Boolean,
    val optionChecked: Boolean
)

object MaterialDialog {
    fun confirm(owner: Window?, title: String, message: String, detail: String? = null): Boolean =
        show(owner, DialogType.CONFIRM, title, message, detail).confirmed

    fun confirmWithOption(
        owner: Window?,
        title: String,
        message: String,
        optionLabel: String,
        detail: String? = null,
        optionDefault: Boolean = false
    ): ConfirmWithOptionResult =
        show(
            owner = owner,
            type = DialogType.CONFIRM,
            title = title,
            message = message,
            detail = detail,
            optionLabel = optionLabel,
            optionDefault = optionDefault
        )

    fun info(owner: Window?, title: String, message: String, detail: String? = null) {
        show(owner, DialogType.INFO, title, message, detail)
    }

    fun error(owner: Window?, title: String, message: String, detail: String? = null) {
        show(owner, DialogType.ERROR, title, message, detail)
    }

    private fun show(
        owner: Window?,
        type: DialogType,
        title: String,
        message: String,
        detail: String?,
        optionLabel: String? = null,
        optionDefault: Boolean = false
    ): ConfirmWithOptionResult {
        val stage = Stage(StageStyle.TRANSPARENT).apply {
            initModality(Modality.WINDOW_MODAL)
            if (owner != null) initOwner(owner)
            isResizable = false
            AppShell.applyAppIcons(this)
        }

        var confirmed = false
        var optionChecked = optionDefault

        val titleLabel = Label(title).apply { styleClass += "dialog-title" }
        val messageLabel = Label(message).apply {
            styleClass += "dialog-message"
            isWrapText = true
            maxWidth = 420.0
        }

        val body = VBox(10.0, titleLabel, messageLabel).apply {
            alignment = Pos.TOP_LEFT
        }

        if (!detail.isNullOrBlank()) {
            val detailLabel = Label(detail).apply {
                styleClass += "dialog-detail"
                isWrapText = true
                maxWidth = 420.0
            }
            if (detail.lines().size > 4) {
                val scroll = ScrollPane(detailLabel).apply {
                    styleClass += "dialog-scroll"
                    isFitToWidth = true
                    prefViewportHeight = minOf(160.0, 24.0 + detail.lines().size * 18.0)
                    hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                }
                body.children += scroll
            } else {
                body.children += detailLabel
            }
        }

        val optionCheck = if (!optionLabel.isNullOrBlank()) {
            CheckBox(optionLabel).apply {
                isSelected = optionDefault
                styleClass += "dialog-option-check"
            }.also { body.children += it }
        } else {
            null
        }

        val cancelButton = Button("取消").apply {
            styleClass += "ghost-button"
            isCancelButton = true
            setOnAction { stage.close() }
        }
        val okButton = Button(
            when (type) {
                DialogType.CONFIRM -> "确认"
                DialogType.ERROR -> "知道了"
                DialogType.INFO -> "好的"
            }
        ).apply {
            styleClass += if (type == DialogType.ERROR) "danger-button" else "primary-button"
            isDefaultButton = true
            setOnAction {
                confirmed = true
                optionChecked = optionCheck?.isSelected == true
                stage.close()
            }
        }

        val actions = HBox(10.0).apply {
            alignment = Pos.CENTER_RIGHT
            children += Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
            if (type == DialogType.CONFIRM) {
                children += cancelButton
            }
            children += okButton
        }

        val card = VBox(18.0, body, actions).apply {
            styleClass += "dialog-card"
            when (type) {
                DialogType.ERROR -> styleClass += "dialog-card-error"
                DialogType.INFO -> styleClass += "dialog-card-info"
                DialogType.CONFIRM -> Unit
            }
            padding = Insets(24.0)
            prefWidth = 480.0
            maxWidth = 480.0
        }

        val overlay = StackPane(card).apply {
            styleClass += "dialog-overlay"
            padding = Insets(28.0)
            alignment = Pos.CENTER
            if (AppShell.isDarkMode()) {
                styleClass += "theme-dark"
            }
        }

        val scene = Scene(overlay).apply {
            fill = Color.TRANSPARENT
            stylesheets += EmbyApplication::class.java.getResource("styles.css")!!.toExternalForm()
            if (AppShell.isDarkMode()) {
                stylesheets += EmbyApplication::class.java.getResource("styles-dark.css")!!.toExternalForm()
            }
        }

        stage.scene = scene
        stage.showAndWait()
        return ConfirmWithOptionResult(confirmed, optionChecked)
    }
}
