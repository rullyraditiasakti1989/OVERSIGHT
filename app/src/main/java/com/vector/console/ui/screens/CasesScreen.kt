package com.vector.console.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vector.console.data.CaseFile
import com.vector.console.data.CasePriority
import com.vector.console.data.CaseStatus
import com.vector.console.data.Store
import com.vector.console.ui.components.ChoiceChip
import com.vector.console.ui.components.DividerLine
import com.vector.console.ui.components.EmptyState
import com.vector.console.ui.components.FieldLabel
import com.vector.console.ui.components.GoldButton
import com.vector.console.ui.components.OutlineActionButton
import com.vector.console.ui.components.PanelCard
import com.vector.console.ui.components.ScreenHeader
import com.vector.console.ui.components.SectionLabel
import com.vector.console.ui.components.SegmentedTabs
import com.vector.console.ui.components.Tag
import com.vector.console.ui.components.VectorTextField
import com.vector.console.ui.theme.Alert
import com.vector.console.ui.theme.Bone
import com.vector.console.ui.theme.BoneDim
import com.vector.console.ui.theme.Green
import com.vector.console.ui.theme.Muted
import com.vector.console.ui.theme.MutedDeep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val caseTabs = listOf("Open", "Active", "Closed")

@Composable
fun CasesScreen(store: Store) {
    var tab by remember { mutableStateOf(0) }
    var showForm by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(CasePriority.LOW) }
    var editingId by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    val statuses = when (tab) {
        0 -> listOf(CaseStatus.OPEN)
        1 -> listOf(CaseStatus.ACTIVE)
        2 -> listOf(CaseStatus.CLOSED)
        else -> emptyList()
    }
    val list = store.cases.filter { it.status in statuses }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll)
            .padding(horizontal = 16.dp, vertical = 18.dp)
    ) {
        ScreenHeader(
            kicker = "File 05 · Cases",
            title = "Cases",
            subtitle = "Structured notes, stored on the device. Nothing syncs."
        )
        Spacer(Modifier.height(20.dp))
        SegmentedTabs(options = caseTabs, selected = tab, onSelect = { tab = it })

        Spacer(Modifier.height(18.dp))
        GoldButton(
            label = if (showForm) "Close form" else "New case",
            onClick = {
                showForm = !showForm
                if (!showForm) {
                    editingId = null
                    title = ""
                    subject = ""
                    notes = ""
                    priority = CasePriority.LOW
                }
            }
        )

        if (showForm) {
            Spacer(Modifier.height(12.dp))
            PanelCard(header = if (editingId == null) "New case" else "Edit case") {
                FieldLabel("Title")
                Spacer(Modifier.height(6.dp))
                VectorTextField(value = title, onValueChange = { title = it }, placeholder = "Case title")
                Spacer(Modifier.height(10.dp))
                FieldLabel("Subject")
                Spacer(Modifier.height(6.dp))
                VectorTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    placeholder = "Name, number, domain, or location"
                )
                Spacer(Modifier.height(12.dp))
                FieldLabel("Priority")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CasePriority.entries.forEach { item ->
                        ChoiceChip(
                            label = item.label,
                            selected = priority == item,
                            onClick = { priority = item }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                FieldLabel("Notes")
                Spacer(Modifier.height(6.dp))
                VectorTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = "Findings, links, context, next step",
                    singleLine = false,
                    minLines = 4
                )
                Spacer(Modifier.height(14.dp))
                GoldButton(
                    label = "Save case",
                    enabled = title.isNotBlank(),
                    onClick = {
                        store.saveCase(
                            CaseFile(
                                id = editingId ?: UUID.randomUUID().toString(),
                                title = title.trim(),
                                subject = subject.trim(),
                                priority = priority,
                                status = when (tab) {
                                    0 -> CaseStatus.OPEN
                                    1 -> CaseStatus.ACTIVE
                                    2 -> CaseStatus.CLOSED
                                    else -> CaseStatus.OPEN
                                },
                                notes = notes.trim()
                            )
                        )
                        editingId = null
                        title = ""
                        subject = ""
                        notes = ""
                        priority = CasePriority.LOW
                        showForm = false
                    }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(label = "Case files", trailing = "${list.size} ${if (list.size == 1) "ENTRY" else "ENTRIES"}")
        Spacer(Modifier.height(10.dp))

        if (list.isEmpty()) {
            EmptyState("No cases", "Nothing in this drawer yet. Open a case to begin.")
        }

        if (list.isNotEmpty()) {
            PanelCard(padding = 0.dp) {
                list.forEachIndexed { index, caseFile ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = (index + 1).toString().padStart(2, '0'),
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedDeep,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = caseFile.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Bone
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = caseFile.subject.ifBlank { "No subject recorded" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Tag(
                                text = caseFile.priority.label,
                                color = when (caseFile.priority) {
                                    CasePriority.LOW -> Muted
                                    CasePriority.AMBER -> BoneDim
                                    CasePriority.RED -> Alert
                                }
                            )
                        }

                        if (caseFile.notes.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(com.vector.console.ui.theme.PanelDeep)
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = caseFile.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = BoneDim
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(10.dp)
                                    .background(if (caseFile.status == CaseStatus.CLOSED) MutedDeep else Green)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = caseFile.status.label + " · " + SimpleDateFormat(
                                    "dd MMM yyyy · HH:mm",
                                    Locale.US
                                ).format(Date(caseFile.updatedAt)).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedDeep
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlineActionButton(
                                label = "Edit",
                                onClick = {
                                    editingId = caseFile.id
                                    title = caseFile.title
                                    subject = caseFile.subject
                                    notes = caseFile.notes
                                    priority = caseFile.priority
                                    showForm = true
                                },
                                modifier = Modifier.weight(1f)
                            )
                            OutlineActionButton(
                                label = "Advance",
                                onClick = {
                                    val nextStatus = when (caseFile.status) {
                                        CaseStatus.OPEN -> CaseStatus.ACTIVE
                                        CaseStatus.ACTIVE -> CaseStatus.CLOSED
                                        CaseStatus.CLOSED -> CaseStatus.CLOSED
                                    }
                                    store.saveCase(caseFile.copy(status = nextStatus))
                                },
                                modifier = Modifier.weight(1f)
                            )
                            OutlineActionButton(
                                label = "Delete",
                                onClick = { store.deleteCase(caseFile.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    if (index != list.lastIndex) DividerLine()
                }
            }
        }

        Spacer(Modifier.height(28.dp))
    }
}