package me.cortex.voxy.client.core.gl;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.ThreadUtils;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_FLOAT;
import static org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST;
import static org.lwjgl.opengl.GL11C.GL_STENCIL_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glGetBoolean;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL11C.glReadPixels;
import static org.lwjgl.opengl.GL11C.glStencilMask;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL15C.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL15C.glBufferSubData;
import static org.lwjgl.opengl.GL15C.glDeleteBuffers;
import static org.lwjgl.opengl.GL15C.glGenBuffers;
import static org.lwjgl.opengl.GL15C.glGetBufferSubData;
import static org.lwjgl.opengl.GL20C.GL_BACK;
import static org.lwjgl.opengl.GL20C.GL_FRONT;
import static org.lwjgl.opengl.GL20C.GL_STENCIL_BACK_WRITEMASK;
import static org.lwjgl.opengl.GL20C.glStencilMaskSeparate;
import static org.lwjgl.opengl.GL30C.GL_DEPTH;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_STENCIL;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_STENCIL;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glClearBufferfi;
import static org.lwjgl.opengl.GL30C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30C.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL30C.nglClearBufferfv;
import static org.lwjgl.opengl.GL30C.nglClearBufferiv;
import static org.lwjgl.opengl.GL30C.GL_R32UI;
import static org.lwjgl.opengl.GL30C.GL_R8UI;
import static org.lwjgl.opengl.GL30C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER_BINDING;
import static org.lwjgl.opengl.GL43C.nglClearBufferData;
import static org.lwjgl.opengl.GL43C.nglClearBufferSubData;
import static org.lwjgl.opengl.GL45C.nglClearNamedBufferData;
import static org.lwjgl.opengl.GL45C.nglClearNamedBufferSubData;
import static org.lwjgl.opengl.GL45C.nglClearNamedFramebufferfv;

/**
 * Clears, routed around a driver bug.
 *
 * <p>Voxy clears its scratch resources exclusively through the direct-state-access entry points -
 * {@code glClearNamedFramebufferfv/iv/fi} for the depth and stencil attachments and
 * {@code glClearNamedBufferData/SubData} for the CPU-written scratch buffers. Intel's Windows
 * OpenGL driver can accept those calls, report no GL error, and leave the resource untouched.
 *
 * <p>The clears that matter to LOD water are:
 * <ul>
 *   <li>{@code ChunkBoundRenderer}'s per-frame reset of {@code viewport.depthBoundingBuffer} - the
 *       mask {@code quads.frag} uses as a depth ceiling. Only the translucent variant reads it
 *       unconditionally ({@code #ifdef TRANSLUCENT -> useChunkBounds = true}), which is why a mask
 *       that is never reset culls LOD fluids while opaque LOD terrain keeps rendering.</li>
 *   <li>{@code MDICSectionRenderer}'s per-frame {@code distanceCountBuffer.zeroRange(0, 1024*4)} -
 *       the 1024 distance buckets the translucent draws are sorted into. They are only ever cleared
 *       through this DSA call, so a dropped clear lets every bucket counter accumulate across
 *       frames: the prefix sum then hands out offsets far past the translucent command region and
 *       the translucent draws stop pointing at real commands. That kills exactly the translucent
 *       LOD - water and ice - while leaving opaque terrain, which is counted by {@code prep.comp}
 *       instead of by a clear, untouched.</li>
 * </ul>
 *
 * <p>Upstream voxy tracks the framebuffer half as MCRcortex/voxy#656 and #626, where the same
 * fallback (bind the resource, then clear through the pre-DSA entry points) was verified on Intel
 * Arc hardware. {@link #init()} additionally probes each clear variant on the running driver and
 * logs which ones are actually dropped, so the diagnosis is visible in {@code latest.log} instead of
 * having to be inferred from the picture.
 *
 * <p>{@code -Dvoxy.disableDsaClearFallback=true} forces the named paths back on, and
 * {@code -Dvoxy.dsaClearFallback=true} forces them off for a driver that is not detected as Intel.
 */
public final class DsaClearPath {
    private static final boolean FORCED_OFF = Boolean.getBoolean("voxy.disableDsaClearFallback");
    private static final boolean FORCED_ON = Boolean.getBoolean("voxy.dsaClearFallback");

    private static boolean resolved;
    private static boolean fallback;

    private DsaClearPath() {}

    /**
     * Render thread only: detection reads the GL vendor string and (once) touches the GL context.
     * Called during renderer construction so the one-off driver probe cannot land in the middle of a
     * frame; every other entry point below resolves lazily from the cached result.
     */
    public static void init() {
        useFallback();
    }

    /** Whether clears have to go through the bind-then-clear path instead of the DSA entry points. */
    public static boolean useFallback() {
        if (!resolved) {
            resolved = true;
            boolean intelWindows = Capabilities.INSTANCE.isIntel && ThreadUtils.isWindows;
            fallback = !FORCED_OFF && (intelWindows || FORCED_ON);
            if (fallback) {
                Logger.info("Clears will use the non-DSA path: glClearNamedFramebuffer*/glClearNamedBuffer* are"
                        + " unreliable on Intel's Windows OpenGL driver and can silently leave a resource stale,"
                        + " which culls the LOD water past the vanilla render distance");
            }
            //Diagnostic only - the decision above is taken from the vendor string, because the driver can
            //start dropping clears later in a session even when the first one works.
            if (intelWindows || FORCED_ON) {
                runSelfTest();
            }
        }
        return fallback;
    }

    //----------------------------------------------------------------------------------------------
    //Framebuffer clears

    /** Clears only the depth attachment of {@code framebuffer} through the non-DSA path. */
    public static void clearDepth(int framebuffer, float depth) {
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        if (scissor) glDisable(GL_SCISSOR_TEST);
        if (!depthWrite) glDepthMask(true);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        try (var stack = MemoryStack.stackPush()) {
            nglClearBufferfv(GL_DEPTH, 0, stack.nfloat(depth));
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        if (!depthWrite) glDepthMask(false);
        if (scissor) glEnable(GL_SCISSOR_TEST);
    }

    /** Clears only the stencil attachment of {@code framebuffer} through the non-DSA path. */
    public static void clearStencil(int framebuffer, int stencil) {
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        int frontMask = glGetInteger(GL_STENCIL_WRITEMASK);
        int backMask = glGetInteger(GL_STENCIL_BACK_WRITEMASK);
        if (scissor) glDisable(GL_SCISSOR_TEST);
        if (frontMask != 0xFF || backMask != 0xFF) glStencilMask(0xFF);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        try (var stack = MemoryStack.stackPush()) {
            nglClearBufferiv(GL_STENCIL, 0, stack.nint(stencil));
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        if (frontMask != 0xFF || backMask != 0xFF) restoreStencilMasks(frontMask, backMask);
        if (scissor) glEnable(GL_SCISSOR_TEST);
    }

    /** Clears the packed depth/stencil attachment of {@code framebuffer} through the non-DSA path. */
    public static void clearDepthStencil(int framebuffer, float depth, int stencil) {
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean depthWrite = glGetBoolean(GL_DEPTH_WRITEMASK);
        int frontMask = glGetInteger(GL_STENCIL_WRITEMASK);
        int backMask = glGetInteger(GL_STENCIL_BACK_WRITEMASK);
        if (scissor) glDisable(GL_SCISSOR_TEST);
        if (!depthWrite) glDepthMask(true);
        if (frontMask != 0xFF || backMask != 0xFF) glStencilMask(0xFF);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        glClearBufferfi(GL_DEPTH_STENCIL, 0, depth, stencil);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
        if (frontMask != 0xFF || backMask != 0xFF) restoreStencilMasks(frontMask, backMask);
        if (!depthWrite) glDepthMask(false);
        if (scissor) glEnable(GL_SCISSOR_TEST);
    }

    //glStencilMask() writes both faces at once, so the two masks have to be split back apart
    private static void restoreStencilMasks(int frontMask, int backMask) {
        if (frontMask == backMask) {
            glStencilMask(frontMask);
        } else {
            glStencilMaskSeparate(GL_FRONT, frontMask);
            glStencilMaskSeparate(GL_BACK, backMask);
        }
    }

    //----------------------------------------------------------------------------------------------
    //Buffer clears (the fallback itself only; the DSA calls stay at the call sites)

    /** The non-DSA equivalent of {@code glClearNamedBufferSubData(buffer, ...)} with a null value. */
    public static void clearBufferSubData(int buffer, int internalFormat, long offset, long size, int format, int type) {
        int previous = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        nglClearBufferSubData(GL_SHADER_STORAGE_BUFFER, internalFormat, offset, size, format, type, 0);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, previous);
    }

    /** The non-DSA equivalent of {@code glClearNamedBufferData(buffer, ...)} with a value pointer. */
    public static void clearBufferData(int buffer, int internalFormat, int format, int type, long valuePtr) {
        int previous = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
        nglClearBufferData(GL_SHADER_STORAGE_BUFFER, internalFormat, format, type, valuePtr);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, previous);
    }

    //----------------------------------------------------------------------------------------------
    //Driver probes

    /**
     * One-off, purely diagnostic check of each clear variant voxy relies on. Every probe writes a
     * value the resource cannot contain by accident, clears it, and reads it back. Failures are
     * reported instead of changing which path is used, because the fallback is already selected from
     * the vendor string - a driver can start dropping clears later in a session even when the first
     * one works.
     */
    private static void runSelfTest() {
        probeFramebufferDepthClear();
        probeBufferClear("glClearNamedBufferSubData(R8UI) - the translucent distance buckets", GL_R8UI, true, false);
        probeBufferClear("glClearNamedBufferSubData(R32UI) - the section render-list counter", GL_R32UI, true, true);
        probeBufferClear("glClearNamedBufferData(R8UI)", GL_R8UI, false, false);
    }

    private static void probeFramebufferDepthClear() {
        final float wanted = 0.25f;
        int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int texture = 0;
        int framebuffer = 0;
        try {
            texture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, 1, 1, 0, GL_DEPTH_COMPONENT, GL_FLOAT, (ByteBuffer) null);
            framebuffer = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texture, 0);
            try (var stack = MemoryStack.stackPush()) {
                nglClearNamedFramebufferfv(framebuffer, GL_DEPTH, 0, stack.nfloat(wanted));
            }
            float readBack;
            try (var stack = MemoryStack.stackPush()) {
                var target = stack.mallocFloat(1);
                glReadPixels(0, 0, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, target);
                readBack = target.get(0);
            }
            report("glClearNamedFramebufferfv - the chunk-bound depth mask", Math.abs(readBack - wanted) <= 1.0e-3f, readBack, wanted);
        } catch (Throwable t) {
            Logger.warn("Could not probe glClearNamedFramebufferfv: " + t);
        } finally {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            if (framebuffer != 0) glDeleteFramebuffers(framebuffer);
            glBindTexture(GL_TEXTURE_2D, previousTexture);
            if (texture != 0) glDeleteTextures(texture);
        }
    }

    private static void probeBufferClear(String label, int internalFormat, boolean subData, boolean wordSized) {
        final int size = 256;
        int buffer = 0;
        int previousSsbo = glGetInteger(GL_SHADER_STORAGE_BUFFER_BINDING);
        int previousArray = glGetInteger(GL_ARRAY_BUFFER);
        try {
            buffer = glGenBuffers();
            ByteBuffer fill = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            for (int i = 0; i < size; i++) fill.put(i, (byte) 0xFF);
            glBindBuffer(GL_ARRAY_BUFFER, buffer);
            glBufferSubData(GL_ARRAY_BUFFER, 0, fill);
            glBindBuffer(GL_ARRAY_BUFFER, previousArray);

            if (subData) {
                nglClearNamedBufferSubData(buffer, internalFormat, 0, size, GL_RED_INTEGER, wordSized ? GL_UNSIGNED_INT : GL_UNSIGNED_BYTE, 0);
            } else {
                nglClearNamedBufferData(buffer, internalFormat, GL_RED_INTEGER, wordSized ? GL_UNSIGNED_INT : GL_UNSIGNED_BYTE, 0);
            }

            ByteBuffer readBack = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffer);
            glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, readBack);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, previousSsbo);
            boolean cleared = true;
            for (int i = 0; i < size; i++) {
                if (readBack.get(i) != 0) {
                    cleared = false;
                    break;
                }
            }
            report(label, cleared, cleared ? 0 : (readBack.get(0) & 0xFF), 0);
        } catch (Throwable t) {
            Logger.warn("Could not probe " + label + ": " + t);
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, previousArray);
            if (buffer != 0) glDeleteBuffers(buffer);
        }
    }

    private static void report(String label, boolean ok, double got, double wanted) {
        if (ok) {
            Logger.info("Driver check: " + label + " works on this driver (read back " + got + ")");
        } else {
            Logger.info("Driver check: " + label + " was DROPPED by this driver (read back " + got + " instead of "
                    + wanted + ") - this is the clear the water path depends on, and the fallback above is required");
        }
    }
}
