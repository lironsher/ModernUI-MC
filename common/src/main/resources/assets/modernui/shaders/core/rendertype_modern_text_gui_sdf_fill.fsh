#version 150
// This file is part of Modern UI.
// Copyright (C) 2024 BloCamLimb.
// Licensed under LGPL-3.0-or-later.

// MC 26.2 GUI variant of rendertype_modern_text_sdf_fill: dedicated fog-free FS.
// See rendertype_modern_text_gui_normal.fsh for why a separate file is needed in 26.2.
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    // must be BILINEAR sampling
    vec4 texColor = textureLod(Sampler0, texCoord0, 0.0);

    // apply distance field
    float dist = texColor.a - 127./255. + 0.04;

    // Minecraft uses non-premultiplied alpha blending
    texColor.a = clamp(dist / fwidth(dist) + 0.5, 0.0, 1.0);

    vec4 color = texColor * vertexColor * ColorModulator;
    if (color.a < 0.01) discard; // requires alpha test
    fragColor = color;
}
