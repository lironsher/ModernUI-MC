# Porting ModernUI-MC (Fabric) to Minecraft 26.2 — dev handoff

Branch: `feat/mc-26.2` (fork: `lironsher/ModernUI-MC`). Target: produce a working
**Fabric** ModernUI jar for MC **26.2** so the Poofy mod can use the in-game UI on its
`26.2.x` Stonecutter variant.

## TL;DR status (updated — 142 → 22 errors)
- ✅ Build infra retargeted to 26.2, all deps resolve, Fabric-only (Forge/NeoForge dropped).
- ✅ Mechanical renames, screen/overlay/chat relocation (Gui/Hud), textures (`GpuFormat`),
  and shader/uniform pipeline (`RenderPipeline.Builder` bind-groups) — all landed & pushed, compile clean.
- ⛔ **22 unique errors remain, all in ONE coherent area: the legacy in-world (immediate-mode) text path.**
  26.2 deleted `MultiBufferSource`, `Font.drawInBatch`, and `Minecraft.renderBuffers()`. The **GUI** text
  path already runs on 26.x render-state (`ModernPreparedText`/`MixinGuiTextRenderState`); only the
  **in-world** path is unported. Files: `ModernTextRenderer`, `TextLayout`, `GlyphManagerForge`,
  `text/mixin/MixinFontRenderer`, `text/mixin/AccessBufferSource`.
- ⚠️ **Needs the dev client** to finish: in-world text now goes through
  `OrderedSubmitNodeCollector.submitText(...)`; reimplement ModernUI's in-world rendering onto it
  (mirror the done GUI path) and delete the obsolete `MixinFontRenderer`/`AccessBufferSource`. Also
  verify Step-4 shader **bind-group order / GLSL `layout(set=)` indices** at runtime (mirrored from vanilla,
  unverified), and re-point `MixinMinecraft`'s stale screen shadow/inject to `Gui.setScreen` (see Step 1).

## Environment / how to build
```bash
cd ~/src/modernui/ModernUI-MC                 # fork, branch feat/mc-26.2
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home   # JDK 25 required
./gradlew :ModernUI-Fabric:compileJava --no-daemon --console=plain   # fast iterate (compile only)
./gradlew :ModernUI-Fabric:build     --no-daemon --console=plain     # full jar
./gradlew :ModernUI-Fabric:runClient --no-daemon                     # dev client (for runtime/mixin testing later)
```
Deobfuscated 26.2 Minecraft source-of-truth for looking up new names:
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`
```bash
jar tf "$JAR" | grep -i SomeClass            # find a class
unzip -oq "$JAR" 'path/To/Class.class' && javap -p path/To/Class.class   # inspect members
```
Note: errors are double-reported (Fabric compile + common platform compile), so raw count
≈ 2× unique. ~53 unique remain.

## Already done (commits on this branch)
1. `build: retarget Fabric module to Minecraft 26.2` — gradle.properties + fabric/build.gradle
   dep bumps; settings.gradle drops forge/neoforge.
   - fabric-loader `0.19.3`, fabric-api `0.153.0+26.2`, modmenu `20.0.0-beta.4`,
     forgeconfigapiport `26.2.1`. arc3d `2026.2.0`/core `3.13.0`/Markflow `3.12.0` unchanged
     (framework is MC-independent). VulkanMod left at `0.6.6` (26.1.2) — no 26.2 build yet;
     it's a compile-only integration dep, revisit if runtime needs it.
2. `port: mechanical Minecraft 26.2 API renames` — 8 files, 1:1 renames (see commit body).

## Remaining work — do it in this order

### Step 1 — Screen/overlay/chat relocation + getChar accessor (RESOLVED — concrete answers below)
26.2 moved screen/overlay management **off `Minecraft` onto `Gui`**, and chat **onto a new `Hud`**.
All replacements are public (no new accessor mixins needed except ChatFormatting). Verified in jar:
- `minecraft.screen` (field removed) → **`minecraft.gui.screen()`** (`Gui.screen()`/`Gui.setScreen(Screen)`).
  `Minecraft.gui` is still `public final Gui`. Fixes `UIManager.java:842`, `OptiFineIntegration.java:56`.
- `minecraft.getOverlay()` → **`minecraft.gui.overlay()`** (already applied in mechanical commit).
- `minecraft.setScreen(s)` → **`minecraft.setScreenAndShow(s)`** (already applied; it delegates to `Gui.setScreen`).
- `minecraft.gui.getChat()` → **`minecraft.gui.hud.getChat()`** — chat moved to `net.minecraft.client.gui.Hud`
  (`Gui.hud` is `public final Hud`; `Hud.getChat() : ChatComponent`; `addClientSystemMessage` unchanged).
  Fixes `UIManager.java:816, 1221`.
- `ChatFormatting.getChar()` removed; backing `code` is `private final char`. Add an interface mixin
  `AccessChatFormatting` with `@Accessor("code") char getCode();` (pattern: `mixin/AccessOptions.java`),
  register it in the mixins json, use `((AccessChatFormatting) (Object) fmt).getCode()`. Fixes `MuiModApi.java:455,456`.
- RUNTIME (not compile): `MixinMinecraft` `@Shadow public Screen screen` + `@Inject(method="setScreen"…)` are
  now stale (field gone, method renamed). Re-point screen-change detection to **`Gui.setScreen`** (new
  `MixinGui` or move the inject) so `MuiModApi.dispatchOnScreenChange` still fires.

### Step 2 — Texture system: `TextureFormat` → `GpuFormat` (GlTexture_Wrapped, ModernFontAtlas; ~10 errors)
26.2 removed `com.mojang.blaze3d.textures.TextureFormat`. Replacements (verified in jar):
- Format type is now **`com.mojang.blaze3d.GpuFormat`** with constants like `RGBA8_UNORM`, `R8_UNORM`
  (note the `_UNORM` suffix — old `TextureFormat.RGBA8` → `GpuFormat.RGBA8_UNORM`). `GpuTexture.getFormat()` returns `GpuFormat`.
- `GpuTexture(int usage, String label, GpuFormat, int w, int h, int depth, int mips)`. Prefer creating via
  `RenderSystem.getDevice().createTexture(String, int usage, GpuFormat, w, h, depth, mips)` rather than `new`.
- `CommandEncoder.writeToTexture(...)` **dropped the `Format` arg**: `writeToTexture(GpuTexture, NativeImage)`,
  `(GpuTexture, NativeImage, int,int,int,int)`, `(GpuTexture, ByteBuffer, int,int,int,int,int,int)`.
  At `ModernFontAtlas.java:209` drop the `Format` argument.
- `getMaxTextureSize()` → check `GpuDevice` (`RenderSystem.getDevice()`); `getBackendName()` →
  **`RenderSystem.getBackendDescription()`**.
- ⚠️ `GlTexture_Wrapped extends GlTexture`: the 26.2 `GlTexture` ctor is now
  `protected GlTexture(int, String, GpuFormat, int w, int h, int depth, int mips, int, FrameBufferCache)`
  (extra `int` + `FrameBufferCache`). Subclassing is awkward — consider whether ModernUI still needs to
  wrap a raw GL handle, or can hold a `GpuTexture`/`GpuTextureView` directly. May need rethink, not a rename.

### Step 3 — THE BIG ONE: text render pipeline `MultiBufferSource` → render-state (most files; ~30 errors)
`net.minecraft.client.renderer.MultiBufferSource` was **removed**. Minecraft 26.2 prepares text into
a retained structure and submits it through a collector. Key new API (verified in jar):
- `Font.prepareText(...) : Font.PreparedText` (replaces `drawInBatch`). `PreparedText` is an interface:
  `void visit(Font.GlyphVisitor)` + `ScreenRectangle bounds()`.
- `Font.GlyphVisitor`: `acceptGlyph(TextRenderable$Styled)`, `acceptEffect/acceptRenderable(TextRenderable)`,
  `acceptEmptyArea(EmptyArea)`. Glyphs are now `net.minecraft.client.gui.font.TextRenderable`.
- `net.minecraft.client.renderer.OrderedSubmitNodeCollector.submitText(PoseStack, float x, float y,
  FormattedCharSequence, boolean dropShadow, Font.DisplayMode, int packedLight, int color, int bgColor, int outline)`
  — the high-level submit path (obtained via `SubmitNodeCollector`).
- GUI text render state lives in `net.minecraft.client.gui.render.state.GuiTextRenderState` — its
  `text` field is now **private** (`MixinActiveTextCollector.java:58`); add an `@Accessor`.
- `AccessBufferSource` (mixin on `MultiBufferSource.BufferSource`) — target class is gone; rework or drop
  whatever buffer-source interception it did against the new state system.

**Migration approach:** ModernUI substitutes its own arc3d GPU text renderer for MC's. Re-point its
`Font`/`FontRenderer` mixins (`MixinFontRenderer`) from the old `drawInBatch(...MultiBufferSource...)`
target to the new `prepareText`/`GlyphVisitor` (or `submitText`) hook, and replace `MultiBufferSource`
parameters in `ModernTextRenderer`/`TextLayout`/`GlyphManagerForge` with the new submission handle.
Study how upstream ModernUI handled the analogous 1.21.x→26.1 immediate-mode changes for the pattern.

### Step 4 — Shaders/uniforms: `RenderPipeline` uniform API + GL (GuiRenderType, TextRenderType, UIManager:954; ~12 errors)
- `RenderPipeline.Builder` uniform API changed (`withUniform(String, UniformType)` / `Mode` gone). Inspect
  `com/mojang/blaze3d/pipeline/RenderPipeline$Builder.class` and the `RenderSystem`/UBO uniform path for the
  new way to declare the `UNIFORM_FONT` uniform (`TextLayoutEngine.java`) and pipeline modes.
- `GlStateManager._enableBlend()` → `_enableBlend(int)` now needs a context arg (`UIManager.java:954`).
- `renderBuffers()` removed — find the new flush/submit entry point on the render pipeline.

## Full remaining error inventory (by file)
- `text/ModernTextRenderer.java`, `text/TextLayout.java`, `text/GlyphManagerForge.java`,
  `text/mixin/MixinFontRenderer.java`, `text/mixin/AccessBufferSource.java` — **MultiBufferSource** (Step 3)
- `text/mixin/MixinActiveTextCollector.java` — **GuiTextRenderState.text private** (Step 3)
- `b3d/GlTexture_Wrapped.java`, `text/ModernFontAtlas.java` — **TextureFormat/GpuTexture/writeToTexture** (Step 2)
- `text/TextRenderType.java`, `GuiRenderType.java`, `text/TextLayoutEngine.java` — **shader/uniform `Mode`/`withUniform`/`UNIFORM_FONT`** (Step 4)
- `UIManager.java:954` — **`_enableBlend(int)`** (Step 4)
- `UIManager.java:842,816,1221`, `OptiFineIntegration.java:56`, `MuiModApi.java:455,456` — **accessor residuals** (Step 1)

## Fallback (if the render port proves too costly)
ModernUI mainline `3.13.0.5` already runs on **MC 26.1** unmodified. Poofy could pilot the in-game
panel on a 26.1 Stonecutter variant and recompile to 26.2 once upstream ships it. See the Poofy repo
plan and the `modernui-ingame-ui` memory.
