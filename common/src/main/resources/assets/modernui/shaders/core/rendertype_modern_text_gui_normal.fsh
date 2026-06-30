#version 150
// This file is part of Modern UI.
// Copyright (C) 2024 BloCamLimb.
// Licensed under LGPL-3.0-or-later.

// MC 1.21.11 GUI variant: dedicated fog-free fragment shader. The shared
// rendertype_modern_text_normal.fsh guards fog behind #if !defined(IS_GUI), but in
// 1.21.11 the pipeline's withShaderDefine("IS_GUI") does not reach our (cached) FS, so the
// fog branch leaked in and failed to link against the GUI core/text VS (which DOES drop
// the fog varyings under IS_GUI). This file simply has no fog path at all.
#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    // lodBias should be -0.5/guiScale in Minecraft normalized GUI coordinates
    // lodBias guarantees NEAREST sampling (sharpen) in despite of float errors
    vec4 texColor = texture(Sampler0, texCoord0, -0.11875);
    vec4 color = texColor * vertexColor * ColorModulator;
    if (color.a < 0.01) discard;
    fragColor = color;
}
