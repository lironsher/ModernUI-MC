/*
 * Modern UI.
 * Copyright (C) 2019-2023 BloCamLimb. All rights reserved.
 *
 * Modern UI is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Modern UI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Modern UI. If not, see <https://www.gnu.org/licenses/>.
 */

package icyllis.modernui.mc.text;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import icyllis.modernui.mc.ModernUIMod;
import icyllis.modernui.mc.MuiModApi;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import javax.annotation.Nonnull;
import java.util.HashMap;

import static icyllis.modernui.mc.ModernUIMod.LOGGER;
import static icyllis.modernui.mc.text.TextLayoutEngine.MARKER;

/**
 * Fast and modern text render type.
 * <p>
 * MC 1.21.11 port: this version still uses the pre-bind-group-layout
 * {@link RenderPipeline.Builder} API (withUniform / withSampler / withBlend /
 * withVertexFormat / withDepthBias), unlike the 26.2 branch which uses
 * BindGroupLayouts. Render type creation goes through
 * {@link RenderSetup} like 26.2.
 */
public abstract class TextRenderType {

    public static final int MODE_NORMAL = 0; // <- must be zero
    public static final int MODE_SDF_FILL = 1;
    public static final int MODE_SDF_STROKE = 2;
    public static final int MODE_SEE_THROUGH = 3;
    /**
     * Used in 2D rendering, render as {@link #MODE_NORMAL},
     * but we compute font size in device space from CTM.
     *
     * @since 3.8.1
     */
    public static final int MODE_UNIFORM_SCALE = 4; // <- must be power of 2

    public static final RenderPipeline PIPELINE_NORMAL = RenderPipeline.builder()
            .withLocation(ModernUIMod.location("pipeline/modern_text_normal"))
            .withVertexShader(Identifier.withDefaultNamespace("core/rendertype_text_intensity"))
            .withFragmentShader(ModernUIMod.location("core/rendertype_modern_text_normal"))
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withBlend(BlendFunction.TRANSLUCENT)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS)
            .build();

    // 1.21.11 GUI variant: the vanilla text VS always emits fog varyings, so we use a dedicated
    // fog-free fragment shader (rendertype_modern_text_gui_normal) to keep GUI text un-fogged.
    public static final RenderPipeline PIPELINE_GUI_NORMAL = RenderPipeline.builder()
            .withLocation(ModernUIMod.location("pipeline/modern_text_gui_normal"))
            .withVertexShader(Identifier.withDefaultNamespace("core/rendertype_text_intensity"))
            .withFragmentShader(ModernUIMod.location("core/rendertype_modern_text_gui_normal"))
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withBlend(BlendFunction.TRANSLUCENT)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS)
            .build();

    public static final RenderPipeline.Snippet PIPELINE_SDF_SNIPPET = RenderPipeline.builder()
            .withVertexShader(Identifier.withDefaultNamespace("core/rendertype_text_intensity"))
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withSampler("Sampler0")
            .withSampler("Sampler2")
            .withBlend(BlendFunction.TRANSLUCENT)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP, VertexFormat.Mode.QUADS)
            .buildSnippet();

    public static final RenderPipeline PIPELINE_SDF_FILL = RenderPipeline.builder(PIPELINE_SDF_SNIPPET)
            .withLocation(ModernUIMod.location("pipeline/modern_text_sdf_fill"))
            .withFragmentShader(ModernUIMod.location("core/rendertype_modern_text_sdf_fill"))
            .withDepthBias(-1.0F, -10.0F)
            .build();

    public static final RenderPipeline PIPELINE_SDF_STROKE = RenderPipeline.builder(PIPELINE_SDF_SNIPPET)
            .withLocation(ModernUIMod.location("pipeline/modern_text_sdf_stroke"))
            .withFragmentShader(ModernUIMod.location("core/rendertype_modern_text_sdf_stroke"))
            .withDepthBias(-1.0F, -10.0F)
            .build();

    // GUI fog-free SDF fill variant (mirrors PIPELINE_GUI_NORMAL).
    public static final RenderPipeline PIPELINE_GUI_SDF = RenderPipeline.builder(PIPELINE_SDF_SNIPPET)
            .withLocation(ModernUIMod.location("pipeline/modern_text_gui_sdf"))
            .withFragmentShader(ModernUIMod.location("core/rendertype_modern_text_gui_sdf_fill"))
            .withDepthBias(-1.0F, -10.0F)
            .build();

    /**
     * Texture id to render type map
     */
    private static final HashMap<Identifier, RenderType> sNormalTypes = new HashMap<>();
    private static final HashMap<Identifier, RenderType> sSDFFillTypes = new HashMap<>();
    private static final HashMap<Identifier, RenderType> sSDFStrokeTypes = new HashMap<>();
    private static final HashMap<Identifier, RenderType> sVanillaTypes = new HashMap<>();
    private static final HashMap<Identifier, RenderType> sSeeThroughTypes = new HashMap<>();
    private static final HashMap<Identifier, RenderType> sPolygonOffsetTypes = new HashMap<>();

    private static RenderType sFirstSDFFillType;
    private static RenderType sFirstSDFStrokeType;

    @Nonnull
    public static RenderType getOrCreate(Identifier texture, int mode) {
        return switch (mode) {
            case MODE_SDF_FILL -> {
                if (!TextLayoutEngine.sCurrentInWorldRendering || TextLayoutEngine.sUseTextShadersInWorld) {
                    yield sSDFFillTypes.computeIfAbsent(texture, TextRenderType::makeSDFFillType);
                } else {
                    yield sPolygonOffsetTypes.computeIfAbsent(texture, TextRenderType::makePolygonOffsetType);
                }
            }
            case MODE_SDF_STROKE -> sSDFStrokeTypes.computeIfAbsent(texture, TextRenderType::makeSDFStrokeType);
            case MODE_SEE_THROUGH -> sSeeThroughTypes.computeIfAbsent(texture, TextRenderType::makeSeeThroughType);
            default -> {
                if (!TextLayoutEngine.sCurrentInWorldRendering || TextLayoutEngine.sUseTextShadersInWorld) {
                    yield sNormalTypes.computeIfAbsent(texture, TextRenderType::makeNormalType);
                } else {
                    yield sVanillaTypes.computeIfAbsent(texture, TextRenderType::makeVanillaType);
                }
            }
        };
    }

    // compatibility
    @Nonnull
    public static RenderType getOrCreate(Identifier texture, Font.DisplayMode mode, boolean isBitmapFont) {
        return switch (mode) {
            case SEE_THROUGH -> sSeeThroughTypes.computeIfAbsent(texture, TextRenderType::makeSeeThroughType);
            case POLYGON_OFFSET -> sPolygonOffsetTypes.computeIfAbsent(texture, TextRenderType::makePolygonOffsetType);
            default -> isBitmapFont || (TextLayoutEngine.sCurrentInWorldRendering && !TextLayoutEngine.sUseTextShadersInWorld)
                    ? sVanillaTypes.computeIfAbsent(texture, TextRenderType::makeVanillaType)
                    : sNormalTypes.computeIfAbsent(texture, TextRenderType::makeNormalType);
        };
    }

    public static RenderPipeline getPipelineForGui(int mode, boolean isBitmapFont) {
        return switch (mode) {
            case MODE_SDF_FILL -> PIPELINE_GUI_SDF;
            default -> isBitmapFont
                    ? RenderPipelines.GUI_TEXT
                    : PIPELINE_GUI_NORMAL;
        };
    }

    @Nonnull
    private static RenderType makeNormalType(Identifier texture) {
        return MuiModApi.get().createRenderType("modern_text_normal",
                RenderSetup.builder(PIPELINE_NORMAL)
                        .withTexture("Sampler0", texture)
                        .useLightmap()
                        .sortOnUpload()
                        .createRenderSetup());
    }

    @Nonnull
    private static RenderType makeSDFFillType(Identifier texture) {
        RenderType renderType = MuiModApi.get().createRenderType("modern_text_sdf_fill",
                RenderSetup.builder(PIPELINE_SDF_FILL)
                        .withTexture("Sampler0", texture,
                                () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                        .useLightmap()
                        .sortOnUpload()
                        .createRenderSetup());
        if (sFirstSDFFillType == null) {
            assert (sSDFFillTypes.isEmpty());
            sFirstSDFFillType = renderType;
        }
        return renderType;
    }

    @Nonnull
    private static RenderType makeSDFStrokeType(Identifier texture) {
        RenderType renderType = MuiModApi.get().createRenderType("modern_text_sdf_stroke",
                RenderSetup.builder(PIPELINE_SDF_STROKE)
                        .withTexture("Sampler0", texture,
                                () -> RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR))
                        .useLightmap()
                        .sortOnUpload()
                        .createRenderSetup());
        if (sFirstSDFStrokeType == null) {
            assert (sSDFStrokeTypes.isEmpty());
            sFirstSDFStrokeType = renderType;
        }
        return renderType;
    }

    @Nonnull
    private static RenderType makeVanillaType(Identifier texture) {
        return MuiModApi.get().createRenderType("modern_text_vanilla",
                RenderSetup.builder(RenderPipelines.TEXT)
                        .withTexture("Sampler0", texture)
                        .useLightmap()
                        .sortOnUpload()
                        .createRenderSetup());
    }

    @Nonnull
    private static RenderType makeSeeThroughType(Identifier texture) {
        return MuiModApi.get().createRenderType("modern_text_see_through",
                RenderSetup.builder(RenderPipelines.TEXT_SEE_THROUGH)
                        .withTexture("Sampler0", texture)
                        .useLightmap()
                        .sortOnUpload()
                        .createRenderSetup());
    }

    @Nonnull
    private static RenderType makePolygonOffsetType(Identifier texture) {
        return MuiModApi.get().createRenderType("modern_text_polygon_offset",
                RenderSetup.builder(RenderPipelines.TEXT_POLYGON_OFFSET)
                        .withTexture("Sampler0", texture)
                        .useLightmap()
                        .sortOnUpload()
                        .createRenderSetup());
    }

    /**
     * Batch rendering and custom ordering.
     * <p>
     * We use a single atlas for batch rendering to improve performance.
     */
    public static RenderType getFirstSDFFillType() {
        return sFirstSDFFillType;
    }

    /**
     * Similarly, but for outline.
     *
     * @see #getFirstSDFFillType()
     */
    public static RenderType getFirstSDFStrokeType() {
        return sFirstSDFStrokeType;
    }

    public static synchronized void clear(boolean cleanup) {
        sFirstSDFFillType = null;
        sFirstSDFStrokeType = null;
        sNormalTypes.clear();
        sSDFFillTypes.clear();
        sSDFStrokeTypes.clear();
        sVanillaTypes.clear();
        sSeeThroughTypes.clear();
        sPolygonOffsetTypes.clear();
    }

    public static RenderPipeline getPipelineSDFFill() {
        return PIPELINE_SDF_FILL;
    }

    public static synchronized boolean toggleSDFShaders(boolean smart) {
        // Smart (GLSL 400) SDF shaders are not used in the 1.21.11 port.
        if (smart) {
            LOGGER.info(MARKER, "Smart SDF text shaders are not supported on this version");
        }
        return false;
    }
}
