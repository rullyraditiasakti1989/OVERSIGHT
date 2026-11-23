package com.vector.console.ui.theme

import androidx.compose.ui.graphics.Color

// The redaction room: black glass, bone paper, one ink with colour.
// Every value below is a role token — see DESIGN.md. Nothing is picked by eye.

// ground and surfaces
val Ink = Color(0xFF060708)
val PanelDeep = Color(0xFF030405)
val Panel = Color(0xFF0C0E10)
val PanelRaised = Color(0xFF15191C)
val SurfaceHigh = Color(0xFF1F2529)

// rules
val HairlineSoft = Color(0xFF1B2124)
val Hairline = Color(0xFF3A444A)

/** Border that makes a control identifiable — holds 3:1 on every surface. */
val ControlLine = Color(0xFF78847F)

// ink
val Bone = Color(0xFFF3F5F4)
val BoneDim = Color(0xFFC9CFCD)
val Muted = Color(0xFF8C9593)
val MutedDeep = Color(0xFF7A8380)

// the only ink that carries colour
val Seal = Color(0xFFE05A48)
val Alert = Color(0xFFE05A48)

// state
val Green = Color(0xFF6FAF92)
val Blue = Color(0xFF93A9BD)

// legacy names kept so every screen keeps compiling; accent is paper now
val Accent = Bone
val Gold = Bone
val GoldSoft = Color(0xFF3B4144)
