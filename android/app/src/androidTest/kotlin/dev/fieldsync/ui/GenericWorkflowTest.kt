package dev.fieldsync.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fieldsync.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GenericWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createGenericTaskAndCompleteChecklistThroughTheUI() {
        val title = "Generic task ${System.currentTimeMillis()}"
        compose.onNodeWithText("Create task").performClick()
        compose.onNodeWithText("Title").performTextInput(title)
        compose.onNodeWithText("Project (optional)").performScrollTo().performTextInput("Shared project")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Tasks").performClick()
        // Search keeps repeated runs independent of tasks already on the device.
        compose.onNodeWithText("Search tasks or projects").performTextInput(title)
        // The search input also contains the title; select the card, not that input.
        val taskCard = hasText(title) and !hasSetTextAction()
        compose.waitUntil(15_000) { compose.onAllNodes(taskCard).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(taskCard).performScrollTo().performClick()
        compose.onNodeWithText("New checklist item").performScrollTo().performTextInput("Review a document")
        compose.onNodeWithText("Add checklist item").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Review a document").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Pass").performScrollTo().performClick()
        // A lazy list may recycle the label above the visible chip after scrolling.
        // Verify the persisted selection instead of requiring that label on screen.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Pass") and isSelected()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Pass").assertIsSelected()
    }
}
