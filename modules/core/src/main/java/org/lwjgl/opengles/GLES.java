/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.opengles;

import org.lwjgl.egl.EGL;
import org.lwjgl.system.*;

import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.*;

import static java.lang.Math.*;
import static org.lwjgl.opengles.GLES20.*;
import static org.lwjgl.opengles.GLES30.*;
import static org.lwjgl.system.APIUtil.*;
import static org.lwjgl.system.Checks.*;
import static org.lwjgl.system.Checks.checkPointer;
import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.system.ThreadLocalUtil.*;

/**
 * This class must be used before any OpenGL ES function is called. It has the following responsibilities:
 * <ul>
 * <li>Loads the OpenGL ES native library into the JVM process.</li>
 * <li>Creates instances of {@link GLESCapabilities} classes. A {@code GLESCapabilities} instance contains flags for functionality that is available in an
 * OpenGL ES context. Internally, it also contains function pointers that are only valid in that specific OpenGL ES context.</li>
 * <li>Maintains thread-local state for {@code GLESCapabilities} instances, corresponding to OpenGL ES contexts that are current in those threads.</li>
 * </ul>
 *
 * <h3>Library lifecycle</h3>
 * <p>The OpenGL ES library is loaded automatically when this class is initialized. Set the {@link Configuration#EXPLICIT_INIT_OPENGLES} option to override
 * this behavior. Manual loading/unloading can be achieved with the {@link #create} and {@link #destroy} functions. The name of the library loaded can
 * be overridden with the {@link Configuration#LIBRARY_NAME_OPENGLES} option. The maximum OpenGL ES version loaded can be set with the
 * {@link Configuration#MAXVERSION_OPENGLES} option. This can be useful to ensure that no functionality above a specific version is used during development.</p>
 *
 * <h3>GLESCapabilities creation</h3>
 * <p>Instances of {@code GLESCapabilities} can be created with the {@link #createCapabilities} method. An OpenGL ES context must be current in the current
 * thread before it is called. Calling this method is expensive, so the {@code GLESCapabilities} instance should be associated with the OpenGL ES context and
 * reused as necessary.</p>
 *
 * <h3>Thread-local state</h3>
 * <p>Before a function for a given OpenGL ES context can be called, the corresponding {@code GLESCapabilities} instance must be passed to the {@link
 * #setCapabilities} method. The user is also responsible for clearing the current {@code GLESCapabilities} instance when the context is destroyed or made
 * current in another thread.</p>
 *
 * <p>Note that the {@link #createCapabilities} method implicitly calls {@link #setCapabilities} with the newly created instance.</p>
 */
public final class GLES {

	private static final APIVersion MAX_VERSION;

	private static FunctionProvider functionProvider;

	private static CapabilitiesState capsProvider = new GlobalCapabilitiesState();

	static {
		MAX_VERSION = Configuration.getAPIVersion(Configuration.MAXVERSION_OPENGLES);

		if ( !Configuration.EXPLICIT_INIT_OPENGLES.<Boolean>get() )
			create();
	}

	private GLES() {}

	/** Loads the OpenGL ES native library, using the default library name. */
	public static void create() {
		SharedLibrary GLES;
		switch ( Platform.get() ) {
			case LINUX:
				GLES = Library.loadNative(Configuration.LIBRARY_NAME_OPENGLES, "libGLESv2.so.2");
				break;
			case MACOSX:
				GLES = Library.loadNative(Configuration.LIBRARY_NAME_OPENGLES, "GLESv2");
				break;
			case WINDOWS:
				GLES = Library.loadNative(Configuration.LIBRARY_NAME_OPENGLES, "libGLESv2", "GLESv2");
				break;
			default:
				throw new IllegalStateException();
		}
		create(GLES);
	}

	/**
	 * Loads the OpenGL ES native library, using the specified library name.
	 *
	 * @param libName the native library name
	 */
	public static void create(String libName) {
		create(Library.loadNative(libName));
	}

	private static void create(final SharedLibrary GLES) {
		try {
			FunctionProvider functionProvider = new FunctionProvider() {
				@Override
				public long getFunctionAddress(CharSequence functionName) {
					long address = EGL.getFunctionProvider().getFunctionAddress(functionName);
					if ( address == NULL ) {
						address = GLES.getFunctionAddress(functionName);
						if ( address == NULL )
							apiLog("Failed to locate address for GLES function " + functionName);
					}

					return address;
				}

				@Override
				public void free() {
					GLES.free();
				}
			};
			create(functionProvider);
		} catch (RuntimeException e) {
			GLES.free();
			throw e;
		}
	}

	/**
	 * Initializes OpenGL ES with the specified {@link FunctionProvider}. This method can be used to implement custom OpenGL ES library loading.
	 *
	 * @param functionProvider the provider of OpenGL ES function addresses
	 */
	public static void create(FunctionProvider functionProvider) {
		if ( GLES.functionProvider != null )
			throw new IllegalStateException("OpenGL ES has already been created.");

		GLES.functionProvider = functionProvider;
	}
	/** Unloads the OpenGL ES native library. */
	public static void destroy() {
		if ( functionProvider == null )
			return;

		functionProvider.free();
		functionProvider = null;
	}

	/** Returns the {@link FunctionProvider} for the OpenGL ES native library. */
	public static FunctionProvider getFunctionProvider() {
		return functionProvider;
	}

	/**
	 * Sets the {@link GLESCapabilities} of the OpenGL ES context that is current in the current thread.
	 *
	 * <p>This {@code GLESCapabilities} instance will be used by any OpenGL ES call in the current thread, until {@code setCapabilities} is called again with
	 * a different value.</p>
	 */
	public static void setCapabilities(GLESCapabilities caps) {
		capsProvider.set(caps);
	}

	/**
	 * Returns the {@link GLESCapabilities} of the OpenGL ES context that is current in the current thread.
	 *
	 * @throws IllegalStateException if {@link #setCapabilities} has never been called in the current thread or was last called with a {@code null} value
	 */
	public static GLESCapabilities getCapabilities() {
		GLESCapabilities caps = capsProvider.get();
		if ( caps == null )
			throw new IllegalStateException("No GLESCapabilities instance has been set for the current thread.");

		return caps;
	}

	/**
	 * Creates a new {@link GLESCapabilities} instance for the OpenGL ES context that is current in the current thread.
	 *
	 * <p>This method calls {@link #setCapabilities(GLESCapabilities)} with the new instance before returning.</p>
	 *
	 * @return the {@code GLESCapabilities} instance
	 *
	 * @throws IllegalStateException if no OpenGL ES context is current in the current thread
	 */
	public static GLESCapabilities createCapabilities() {
		GLESCapabilities caps = null;

		try {
			// We don't have a current GLESCapabilities when this method is called
			// so we have to use the native bindings directly.
			long GetError = functionProvider.getFunctionAddress("glGetError");
			long GetString = functionProvider.getFunctionAddress("glGetString");
			long GetIntegerv = functionProvider.getFunctionAddress("glGetIntegerv");

			if ( GetError == NULL || GetString == NULL || GetIntegerv == NULL )
				throw new IllegalStateException(
					"Core OpenGL ES functions could not be found. Make sure that the OpenGL ES library has been loaded correctly."
				);

			int errorCode = invokeI(GetError);
			if ( errorCode != GL_NO_ERROR )
				apiLog(
					"An OpenGL ES context was in an error state before the creation of its capabilities instance. Error: " + GLESUtil.getErrorString(errorCode)
				);

			int majorVersion;
			int minorVersion;

			MemoryStack stack = stackPush();
			try {
				IntBuffer pi = stack.ints(0);

				// Try the 3.0+ version query first
				invokeIPV(GetIntegerv, GL_MAJOR_VERSION, memAddress(pi));
				if ( invokeI(GetError) == GL_NO_ERROR && 3 <= (majorVersion = pi.get(0)) ) {
					// We're on an 3.0+ context.
					invokeIPV(GetIntegerv, GL_MINOR_VERSION, memAddress(pi));
					minorVersion = pi.get(0);
				} else {
					// Fallback to the string query.
					long versionString = invokeIP(GetString, GL_VERSION);
					if ( versionString == NULL || invokeI(GetError) != GL_NO_ERROR )
						throw new IllegalStateException("There is no OpenGL ES context current in the current thread.");

					APIVersion version = apiParseVersion(memDecodeUTF8(versionString), "OpenGL ES");

					majorVersion = version.major;
					minorVersion = version.minor;
				}
			} finally {
				stack.pop();
			}

			if ( majorVersion < 2 )
				throw new IllegalStateException("OpenGL ES 2.0 is required.");

			int[] GL_VERSIONS = {
				-1, // OpenGL ES 1.0 not supported
				0, // OpenGL ES 2.0
				2 // OpenGL ES 3.0 to 3.2
			};

			Set<String> supportedExtensions = new HashSet<String>(128);

			int maxMajor = min(majorVersion, GL_VERSIONS.length);
			if ( MAX_VERSION != null )
				maxMajor = min(MAX_VERSION.major, maxMajor);
			for ( int M = 2; M <= maxMajor; M++ ) {
				int maxMinor = GL_VERSIONS[M - 1];
				if ( M == majorVersion )
					maxMinor = min(minorVersion, maxMinor);
				if ( MAX_VERSION != null && M == MAX_VERSION.major )
					maxMinor = min(MAX_VERSION.minor, maxMinor);

				for ( int m = 0; m <= maxMinor; m++ )
					supportedExtensions.add(String.format("GLES%d%d", M, m));
			}

			if ( majorVersion < 3 ) {
				// Parse EXTENSIONS string
				String extensionsString = memDecodeASCII(checkPointer(invokeIP(GetString, GL_EXTENSIONS)));

				StringTokenizer tokenizer = new StringTokenizer(extensionsString);
				while ( tokenizer.hasMoreTokens() )
					supportedExtensions.add(tokenizer.nextToken());
			} else {
				// Use indexed EXTENSIONS
				int extensionCount;

				stack.push();
				try {
					IntBuffer pi = stack.ints(0);

					invokeIPV(GetIntegerv, GL_NUM_EXTENSIONS, memAddress(pi));
					extensionCount = pi.get(0);
				} finally {
					stack.pop();
				}

				long GetStringi = checkFunctionAddress(functionProvider.getFunctionAddress("glGetStringi"));
				for ( int i = 0; i < extensionCount; i++ )
					supportedExtensions.add(memDecodeASCII(checkPointer(callIIP(GetStringi, GL_EXTENSIONS, i))));
			}

			caps = new GLESCapabilities(getFunctionProvider(), supportedExtensions);
		} finally {
			setCapabilities(caps);
		}

		return caps;
	}

	static <T> T checkExtension(String extension, T functions, boolean supported) {
		if ( supported )
			return functions;
		else {
			apiLog("[GLES] " + extension + " was reported as available but an entry point is missing.");
			return null;
		}
	}

	/** Manages the thread-local {@link GLESCapabilities} state. */
	private interface CapabilitiesState {
		void set(GLESCapabilities caps);
		GLESCapabilities get();
	}

	/** Default {@link CapabilitiesState} implementation using {@link ThreadLocalUtil.TLS}. */
	private static class TLCapabilitiesState implements CapabilitiesState {
		@Override
		public void set(GLESCapabilities caps) { tlsGet().glesCaps = caps; }

		@Override
		public GLESCapabilities get() { return tlsGet().glesCaps; }
	}

	/**
	 * This is the initial {@link CapabilitiesState}. As long as we do not encounter a {@link GLESCapabilities} instance that is different than the first
	 * instance passed to {@link #setCapabilities} (very unlikely to happen in most programs), we continue using it. This implementation skips the thread-local
	 * lookup and therefore provides a much more efficient {@link #getCapabilities} (but a much slower {@link #setCapabilities}).
	 *
	 * <p>A {@link TLCapabilitiesState} instance is maintained internally. If the above rare condition is triggered, {@link #capsProvider} is switched to that
	 * instance and {@link GlobalCapabilitiesState} is never used again.</p>
	 */
	private static class GlobalCapabilitiesState implements CapabilitiesState {

		// The static final here helps performance if we switch.
		private static final TLCapabilitiesState tlProvider = new TLCapabilitiesState();

		// We need this to able to reset caps to null. This is useful during init; the first OpenGL created is usually a dummy context with different
		// capabilities to what we're actually going to use.
		private final WeakHashMap<Thread, GLESCapabilities> tlMap = new WeakHashMap<Thread, GLESCapabilities>(16);

		private final List<Field> flags;
		private final List<Field> funcs;

		private GLESCapabilities caps;

		GlobalCapabilitiesState() {
			Field[] fields = GLESCapabilities.class.getDeclaredFields();

			this.flags = new ArrayList<Field>(256);
			this.funcs = new ArrayList<Field>(128);

			for ( Field f : fields )
				(f.getType() == Boolean.TYPE ? flags : funcs).add(f);
		}

		@Override
		public synchronized void set(GLESCapabilities caps) {
			tlProvider.set(caps);

			if ( caps == null ) {
				tlMap.remove(Thread.currentThread());
				if ( tlMap.isEmpty() )
					this.caps = null;
			} else {
				if ( tlMap.isEmpty() ) // poll the reference queue, in case a thread didn't call GLES.setCapabilities(null) before exiting
					this.caps = null;

				tlMap.put(Thread.currentThread(), caps);

				if ( this.caps == null )
					this.caps = caps;
				else if ( !apiCompareCapabilities(flags, funcs, this.caps, caps) ) {
					apiLog("An OpenGL ES context with different functionality detected. Switching to thread-local GLCapabilities.");
					capsProvider = tlProvider;
				}
			}
		}

		@Override
		public GLESCapabilities get() {
			return caps;
		}

	}

}