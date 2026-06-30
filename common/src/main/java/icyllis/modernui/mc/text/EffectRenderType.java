/*
 * Modern UI.
 * Copyright (C) 2020-2025 BloCamLimb. All rights reserved.
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

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import icyllis.arc3d.core.ColorInfo;
import icyllis.arc3d.engine.Engine;
import icyllis.arc3d.engine.ISurface;
import icyllis.arc3d.engine.ImageDesc;
import icyllis.arc3d.engine.ImmediateContext;
import icyllis.arc3d.opengl.GLDevice;
import icyllis.arc3d.opengl.GLTexture;
import icyllis.modernui.annotation.RenderThread;
import icyllis.modernui.core.Core;
import icyllis.modernui.mc.ModernUIMod;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.b3d.GlTexture_Wrapped;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.annotation.Nonnull;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Renders text decorations (underline, strikethrough) using a small solid-white
 * texture. On 1.21.11 effects are submitted as {@link TextEffectRenderState}s,
 * which need a {@link GpuTextureView} + {@link GpuSampler} (see
 * {@link net.minecraft.client.gui.render.TextureSetup}) and a {@link RenderType}
 * keyed by an {@link Identifier}; this class owns the white texture and its
 * registration.
 *
 * @since 2.0.1
 */
public final class EffectRenderType {

    /**
     * Identifier under which the white effect texture is registered with the
     * vanilla TextureManager, so it can key a {@link TextRenderType}.
     */
    public static final Identifier WHITE_SHEET = ModernUIMod.location("textures/effect/white.png");

    private static GLTexture WHITE;
    private static GlTexture_Wrapped WHITE_WRAPPER = null;
    private static GpuTextureView WHITE_WRAPPER_VIEW = null;
    private static GlyphManager.AtlasTextureWrapper WHITE_ABSTRACT = null;

    private EffectRenderType() {
    }

    @RenderThread
    @Nonnull
    public static RenderType getRenderType(boolean seeThrough, boolean polygonOffset) {
        if (WHITE == null) {
            makeWhiteTexture();
        }
        Font.DisplayMode mode = polygonOffset
                ? Font.DisplayMode.POLYGON_OFFSET
                : seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL;
        // effects share the vanilla (intensity) text pipeline, like bitmap glyphs
        return TextRenderType.getOrCreate(WHITE_SHEET, mode, /*isBitmapFont*/ true);
    }

    @RenderThread
    @Nonnull
    public static GpuTextureView getTexture() {
        if (WHITE == null) {
            makeWhiteTexture();
        }
        return WHITE_WRAPPER_VIEW;
    }

    @RenderThread
    @Nonnull
    public static GpuSampler getSampler() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }

    public static void clear() {
        if (WHITE != null) {
            Minecraft.getInstance().getTextureManager().release(WHITE_SHEET);
            WHITE_WRAPPER_VIEW.close();
            WHITE_WRAPPER.close();
            WHITE = null;
            WHITE_WRAPPER = null;
            WHITE_WRAPPER_VIEW = null;
            WHITE_ABSTRACT = null;
        }
    }

    private static void makeWhiteTexture() {
        ImmediateContext context = Core.requireImmediateContext();
        final int width = 8, height = 8;
        final int colorType = ColorInfo.CT_RGBA_8888;
        ImageDesc desc = context.getCaps().getDefaultColorImageDesc(
                Engine.ImageType.k2D,
                colorType,
                width, height,
                1,
                ISurface.FLAG_SAMPLED_IMAGE
        );
        Objects.requireNonNull(desc); // RGBA8 is always supported
        WHITE = (GLTexture) context
                .getResourceProvider()
                .findOrCreateImage(
                        desc,
                        /*budgeted*/ false,
                        "WhiteTexture"
                );
        Objects.requireNonNull(WHITE);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int bpp = ColorInfo.bytesPerPixel(colorType);
            ByteBuffer pixels = stack.malloc(width * height * bpp);
            MemoryUtil.memSet(pixels, 0xff);
            boolean res = ((GLDevice) context.getDevice()).writePixels(
                    WHITE, 0, 0, width, height,
                    colorType, colorType,
                    width * bpp,
                    MemoryUtil.memAddress(pixels)
            );
            assert res;
        }

        WHITE_WRAPPER = new GlTexture_Wrapped(WHITE); // transfer ownership
        WHITE_WRAPPER_VIEW = MuiModApi.get().getRealGpuDevice().createTextureView(WHITE_WRAPPER);

        // NEAREST filtering is selected per-bind via GpuSampler (see getSampler()).

        WHITE_ABSTRACT = new GlyphManager.AtlasTextureWrapper(WHITE_WRAPPER_VIEW);
        Minecraft.getInstance().getTextureManager().register(WHITE_SHEET, WHITE_ABSTRACT);
    }
}
