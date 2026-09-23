package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.GL_STENCIL_BACK_WRITEMASK;
import static org.lwjgl.opengl.GL20C.glStencilMaskSeparate;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_PACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL43C.*;
import static org.lwjgl.opengl.GL45C.nglClearNamedBufferData;
import static org.lwjgl.opengl.GL45C.nglClearNamedBufferSubData;
import static org.lwjgl.opengl.GL45C.nglClearNamedFramebufferfv;

/**
 * Bind-then-clear compatibility path. All entry points require the render thread and its GL context.
 *
 * <p>The Intel/Windows policy is retained from the previous water fix. It is a compatibility policy,
 * not a conclusion drawn from the probes below. A passing one-off probe cannot certify a whole
 * session, and a mismatch alone does not establish the cause of a rendering artifact.
 *
 * <p>{@code -Dvoxy.disableDsaClearFallback=true} forces the named paths on;
 * {@code -Dvoxy.dsaClearFallback=true} forces the fallback on. The disable flag takes precedence.
 * Diagnostics cover named depth-fv, buffer SubData R8UI/R32UI and buffer Data R8UI clears only;
 * they do not test stencil-iv, depth/stencil-fi, or every format used by the renderer.
 */
public final class DsaClearPath {
    private static final boolean FORCED_OFF = Boolean.getBoolean("voxy.disableDsaClearFallback");
    private static final boolean FORCED_ON = Boolean.getBoolean("voxy.dsaClearFallback");

    private static boolean resolved;
    private static boolean fallback;

    private DsaClearPath() {}

    public static void init() {
        useFallback();
    }

    public static boolean useFallback() {
        if (!resolved) {
            resolved = true;
            boolean intelWindows = Capabilities.INSTANCE.isIntel && ThreadUtils.isWindows;
            fallback = !FORCED_OFF && (intelWindows || FORCED_ON);
            Logger.info("DSA clear diagnostics v3: path=" + (fallback ? "bind-then-clear" : "named")
                    + ", intelWindows=" + intelWindows + ", forcedOn=" + FORCED_ON + ", forcedOff=" + FORCED_OFF
                    + "; policy is independent of probe results");
            if (intelWindows || FORCED_ON) {
                runSelfTest();
            }
        }
        return fallback;
    }

    /** Clears the entire depth attachment, temporarily overriding scissor and depth write masks. */
    public static void clearDepth(int framebuffer, float depth) {
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        try {
            if (scissor) glDisable(GL_SCISSOR_TEST);
            if (!depthWrite) glDepthMask(true);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            try (var stack = MemoryStack.stackPush()) {
                nglClearBufferfv(GL_DEPTH, 0, stack.nfloat(depth));
            }
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            if (!depthWrite) glDepthMask(false);
            if (scissor) glEnable(GL_SCISSOR_TEST);
        }
    }

    public static void clearStencil(int framebuffer, int stencil) {
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        int frontMask = glGetInteger(GL_STENCIL_WRITEMASK);
        int backMask = glGetInteger(GL_STENCIL_BACK_WRITEMASK);
        try {
            if (scissor) glDisable(GL_SCISSOR_TEST);
            if (frontMask != 0xFF || backMask != 0xFF) glStencilMask(0xFF);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            try (var stack = MemoryStack.stackPush()) {
                nglClearBufferiv(GL_STENCIL, 0, stack.nint(stencil));
            }
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            if (frontMask != 0xFF || backMask != 0xFF) restoreStencilMasks(frontMask, backMask);
            if (scissor) glEnable(GL_SCISSOR_TEST);
        }
    }

    public static void clearDepthStencil(int framebuffer, float depth, int stencil) {
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        int frontMask = glGetInteger(GL_STENCIL_WRITEMASK);
        int backMask = glGetInteger(GL_STENCIL_BACK_WRITEMASK);
        try {
            if (scissor) glDisable(GL_SCISSOR_TEST);
            if (!depthWrite) glDepthMask(true);
            if (frontMask != 0xFF || backMask != 0xFF) glStencilMask(0xFF);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
            glClearBufferfi(GL_DEPTH_STENCIL, 0, depth, stencil);
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            if (frontMask != 0xFF || backMask != 0xFF) restoreStencilMasks(frontMask, backMask);
            if (!depthWrite) glDepthMask(false);
            if (scissor) glEnable(GL_SCISSOR_TEST);
        }
    }

    private static void restoreStencilMasks(int frontMask, int backMask) {
        if (frontMask == backMask) {
            glStencilMask(frontMask);
        } else {
            glStencilMaskSeparate(GL_FRONT, frontMask);
            glStencilMaskSeparate(GL_BACK, backMask);
        }
    }

    public static void clearBufferSubData(int buffer, int internalFormat, long offset, long size, int format, int type) {
        int previous = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            nglClearBufferSubData(GL_SHADER_STORAGE_BUFFER, internalFormat, offset, size, format, type, 0);
        } finally {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, previous);
        }
    }

    public static void clearBufferData(int buffer, int internalFormat, int format, int type, long valuePtr) {
        int previous = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            nglClearBufferData(GL_SHADER_STORAGE_BUFFER, internalFormat, format, type, valuePtr);
        } finally {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, previous);
        }
    }

    private static void runSelfTest() {
        //Separate pre-existing errors from errors caused by our probes. This consumes the GL error
        //queue once at initialization and logs anything that was already pending, rather than hiding it.
        logProbeErrors("pre-existing GL errors before diagnostics (not a probe result)");
        probeFramebufferDepthClear();
        probeBufferClear("glClearNamedBufferSubData(R8UI)", GL_R8UI, true, false);
        probeBufferClear("glClearNamedBufferSubData(R32UI)", GL_R32UI, true, true);
        probeBufferClear("glClearNamedBufferData(R8UI)", GL_R8UI, false, false);
    }

    private static void probeFramebufferDepthClear() {
        final String label = "glClearNamedFramebufferfv(depth)";
        final float initial = 0.75f;
        final float wanted = 0.25f;
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousRenderbuffer = glGetInteger(GL_RENDERBUFFER_BINDING);
        int previousPackBuffer = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int packAlignment = glGetInteger(GL_PACK_ALIGNMENT);
        int packRowLength = glGetInteger(GL_PACK_ROW_LENGTH);
        int packSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        int packSkipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int packSwapBytes = glGetInteger(GL_PACK_SWAP_BYTES);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        int renderbuffer = 0;
        int framebuffer = 0;
        boolean resultValid = false;
        float readBack = Float.NaN;
        try (var stack = MemoryStack.stackPush()) {
            if (scissor) glDisable(GL_SCISSOR_TEST);
            if (!depthWrite) glDepthMask(true);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);
            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SWAP_BYTES, 0);

            //A renderbuffer avoids touching the game's active texture, unpack PBO and unpack state.
            renderbuffer = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, renderbuffer);
            glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, 1, 1);
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, renderbuffer);
            glDrawBuffer(GL_NONE);
            glReadBuffer(GL_NONE);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                Logger.warn("Driver check INCONCLUSIVE: " + label + "; incomplete framebuffer 0x"
                        + Integer.toHexString(status));
                return;
            }
            if (logProbeErrors(label + " setup")) return;

            //Validate a known initial value through the bound path before testing the named path.
            nglClearBufferfv(GL_DEPTH, 0, stack.nfloat(initial));
            var target = stack.mallocFloat(1);
            target.put(0, Float.NaN);
            glReadPixels(0, 0, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, target);
            if (logProbeErrors(label + " baseline")) return;
            if (!(Math.abs(target.get(0) - initial) <= 1.0e-3f)) {
                Logger.warn("Driver check INCONCLUSIVE: " + label + "; baseline readback failed");
                return;
            }

            nglClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, stack.nfloat(wanted));
            target.put(0, Float.NaN);
            glReadPixels(0, 0, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, target);
            if (logProbeErrors(label + " clear/readback")) return;
            readBack = target.get(0);
            resultValid = true;
        } catch (RuntimeException e) {
            Logger.warn("Driver check INCONCLUSIVE: " + label + "; " + e);
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            glBindRenderbuffer(GL_RENDERBUFFER, previousRenderbuffer);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, previousPackBuffer);
            glPixelStorei(GL_PACK_ALIGNMENT, packAlignment);
            glPixelStorei(GL_PACK_ROW_LENGTH, packRowLength);
            glPixelStorei(GL_PACK_SKIP_PIXELS, packSkipPixels);
            glPixelStorei(GL_PACK_SKIP_ROWS, packSkipRows);
            glPixelStorei(GL_PACK_SWAP_BYTES, packSwapBytes);
            if (!depthWrite) glDepthMask(false);
            if (scissor) glEnable(GL_SCISSOR_TEST);
            if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
            if (renderbuffer != 0) glDeleteRenderbuffers(renderbuffer);
            if (logProbeErrors(label + " remaining/cleanup")) resultValid = false;
        }
        if (resultValid) report(label, Math.abs(readBack - wanted) <= 1.0e-3f, readBack, wanted);
    }

    private static void probeBufferClear(String label, int internalFormat, boolean subData, boolean wordSized) {
        final int size = 256;
        int previousArray = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int buffer = 0;
        boolean resultValid = false;
        int mismatchIndex = -1;
        int mismatchValue = 0;
        try (var stack = MemoryStack.stackPush()) {
            buffer = glGenBuffers();
            ByteBuffer fill = stack.malloc(size);
            for (int i = 0; i < size; i++) fill.put(i, (byte) 0xFF);
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            //BufferSubData cannot allocate storage. Allocate AND initialize before any clear/readback.
            glBufferData(GL_ARRAY_BUFFER, fill, GL_STATIC_DRAW);
            ByteBuffer readBack = stack.malloc(size);
            for (int i = 0; i < size; i++) readBack.put(i, (byte) 0x7F);
            glGetBufferSubData(GL_ARRAY_BUFFER, 0, readBack);
            if (logProbeErrors(label + " allocation/baseline")) return;
            for (int i = 0; i < size; i++) {
                if (readBack.get(i) != (byte) 0xFF) {
                    Logger.warn("Driver check INCONCLUSIVE: " + label + "; baseline readback failed at byte " + i);
                    return;
                }
            }

            if (subData) {
                nglClearNamedBufferSubData(buffer, internalFormat, 0, size, GL_RED_INTEGER,
                        wordSized ? GL_UNSIGNED_INT : GL_UNSIGNED_BYTE, 0);
            } else {
                nglClearNamedBufferData(buffer, internalFormat, GL_RED_INTEGER,
                        wordSized ? GL_UNSIGNED_INT : GL_UNSIGNED_BYTE, 0);
            }
            //A failed read must not be mistaken for an all-zero result.
            for (int i = 0; i < size; i++) readBack.put(i, (byte) 0x7F);
            glGetBufferSubData(GL_ARRAY_BUFFER, 0, readBack);
            if (logProbeErrors(label + " clear/readback")) return;
            for (int i = 0; i < size; i++) {
                if (readBack.get(i) != 0) {
                    mismatchIndex = i;
                    mismatchValue = readBack.get(i) & 0xFF;
                    break;
                }
            }
            resultValid = true;
        } catch (RuntimeException e) {
            Logger.warn("Driver check INCONCLUSIVE: " + label + "; " + e);
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, previousArray);
            if (buffer != 0) glDeleteBuffers(buffer);
            if (logProbeErrors(label + " remaining/cleanup")) resultValid = false;
        }
        if (resultValid) {
            report(mismatchIndex < 0 ? label : label + " at byte " + mismatchIndex,
                    mismatchIndex < 0, mismatchValue, 0);
        }
    }

    private static boolean logProbeErrors(String stage) {
        boolean failed = false;
        for (int error = glGetError(); error != GL_NO_ERROR; error = glGetError()) {
            Logger.warn("Driver check INCONCLUSIVE: " + stage + "; GL error 0x" + Integer.toHexString(error));
            failed = true;
        }
        return failed;
    }

    private static void report(String label, boolean ok, double got, double wanted) {
        String result = "Driver check " + (ok ? "PASS: " : "MISMATCH: ") + label
                + "; read back " + got + ", expected " + wanted
                + ". One-off diagnostic only; clear-path policy unchanged.";
        if (ok) Logger.info(result);
        else Logger.warn(result);
    }
}
