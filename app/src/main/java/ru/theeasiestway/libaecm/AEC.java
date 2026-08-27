package ru.theeasiestway.libaecm;

import android.util.Log;

public class AEC {

    private static final String TAG = "AECM_LOG";

    static {
        try {
            System.loadLibrary("AEC");
        } catch (Exception e) {
            Log.d(TAG, "Can't load AECM library: " + e);
        }
    }

    // /////////////////////////////////////////////////////////
    // PUBLIC CONSTANTS

    /**
     * constant unable mode for Aecm configuration settings.
     */
    public static final short AECM_UNABLE = 0;

    /**
     * constant enable mode for Aecm configuration settings.
     */
    public static final short AECM_ENABLE = 1;

    // /////////////////////////////////////////////////////////
    // PUBLIC NESTED CLASSES

    /**
     * For security reason, this class supports constant sampling frequency values in
     * {@link SamplingFrequency#FS_8000Hz FS_8000Hz}, {@link SamplingFrequency#FS_16000Hz FS_16000Hz}
     */
    public static final class SamplingFrequency {
        public int getFS() {
            return mSamplingFrequency;
        }

        /**
         * This constant represents sampling frequency in 8000Hz
         */
        public static final SamplingFrequency FS_8000Hz = new SamplingFrequency(
                8000);

        /**
         * This constant represents sampling frequency in 16000Hz
         */
        public static final SamplingFrequency FS_16000Hz = new SamplingFrequency(
                16000);

        private final int mSamplingFrequency;

        private SamplingFrequency(int fs) {
            this.mSamplingFrequency = fs;
        }
    }

    /**
     * For security reason, this class supports constant aggressiveness of the AECM instance in
     * {@link AggressiveMode#MILD MILD}, {@link AggressiveMode#MEDIUM MEDIUM}, {@link AggressiveMode#HIGH HIGH},
     * {@link AggressiveMode#AGGRESSIVE AGGRESSIVE}, {@link AggressiveMode#MOST_AGGRESSIVE MOST_AGGRESSIVE}.
     */
    public static final class AggressiveMode {
        public int getMode() {
            return mMode;
        }

        /**
         * This constant represents the aggressiveness of the AECM instance in MILD_MODE
         */
        public static final AggressiveMode MILD = new AggressiveMode(0);

        /**
         * This constant represents the aggressiveness of the AECM instance in MEDIUM_MODE
         */
        public static final AggressiveMode MEDIUM = new AggressiveMode(1);

        /**
         * This constant represents the aggressiveness of the AECM instance in HIGH_MODE
         */
        public static final AggressiveMode HIGH = new AggressiveMode(2);

        /**
         * This constant represents the aggressiveness of the AECM instance in AGGRESSIVE_MODE
         */
        public static final AggressiveMode AGGRESSIVE = new AggressiveMode(3);

        /**
         * This constant represents the aggressiveness of the AECM instance in MOST_AGGRESSIVE_MODE
         */
        public static final AggressiveMode MOST_AGGRESSIVE = new AggressiveMode(4);

        private final int mMode;

        private AggressiveMode(int mode) {
            mMode = mode;
        }
    }

    // /////////////////////////////////////////////////////////
    // PRIVATE MEMBERS

    private long              mAecmHandler = -1;    // the handler of AECM instance.
    private AecmConfig        mAecmConfig  = null;  // the configurations of AECM instance.
    private SamplingFrequency mSampFreq    = null;  // sampling frequency of input speech data.
    private boolean           mIsInit      = false; // whether the AECM instance is initialized or not.

    // /////////////////////////////////////////////////////////
    // CONSTRUCTOR

    /**
     * Generate a new AECM instance
     */
    public AEC() {
        mAecmHandler = nativeCreateAecmInstance();
        mAecmConfig = new AecmConfig();
        Log.d(TAG, "AECM instance successfully created");
    }

    /**
     * To generate a new AECM instance, whether you set the sampling frequency of each parameter or not are both ok.
     *
     * @param sampFreqOfData - sampling frequency of input audio data. if null, then {@link SamplingFrequency#FS_16000Hz FS_16000Hz} is set.
     */
    public AEC(SamplingFrequency sampFreqOfData) {
        this(sampFreqOfData, null);
    }

    /**
     * To generate a new AECM instance, whether you set the sampling frequency and aggresive mode or not are both ok.
     *
     * @param sampFreqOfData - sampling frequency of input audio data. if null, then {@link SamplingFrequency#FS_16000Hz FS_16000Hz} is set.
     * @param aggressiveMode - aggressiveness mode of AECM instance, more higher the mode is, more aggressive the instance will be.
     *                       if null, then {@link AggressiveMode#AGGRESSIVE AGGRESSIVE} is set.
     */
    public AEC(SamplingFrequency sampFreqOfData, AggressiveMode aggressiveMode) {
        // create new AECM instance but without initialize. Init things are in prepare() method instead.
        mAecmHandler = nativeCreateAecmInstance();
        setSampFreq(sampFreqOfData, false);
        mAecmConfig = new AecmConfig();
        setAecmMode(aggressiveMode, false);
        Log.d(TAG, "AECM instance successfully created");
        prepare();
    }

    // /////////////////////////////////////////////////////////
    // PUBLIC METHODS

    public void setSampFreq(SamplingFrequency frequency) {
        setSampFreq(frequency, true);
    }

    /**
     * set the sampling rate of speech data.
     *
     * @param frequency - sampling frequency of speech data, if null then {@link SamplingFrequency#FS_16000Hz FS_16000Hz} is set.
     * @param prepare - is flag that indicate will or will not prepare AECM instance after sampling frequency was set.
     */
    private void setSampFreq(SamplingFrequency frequency, boolean prepare) {
        if (frequency == null) {
            Log.d(TAG, "setSampFreq() frequency == null, SamplingFrequency.FS_16000Hz will be used instead");
            mSampFreq = SamplingFrequency.FS_16000Hz;
        }
        else mSampFreq = frequency;
        if (prepare) prepare();
    }

    /**
     * set the far-end signal of AECM instance.
     *
     * @param farendFrame
     * @param frameLength
     * @return the {@link AEC AEC} object itself or null if farendBuffer() is called on an unprepared AECM instance
     * or you pass an invalid parameter.
     */
    public AEC farendBuffer(short[] farendFrame, int frameLength) {
        // check if AECM instance is not initialized.
        if (!mIsInit) {
            Log.d(TAG, "farendBuffer() is called on an unprepared AECM instance or you pass an invalid parameter");
            return null;
        }

        if (nativeBufferFarend(mAecmHandler, farendFrame, frameLength) == -1) {
            Log.d(TAG, "farendBuffer() failed due to invalid arguments");
            return null;
        }

        return this;
    }

    /**
     * core process of AECM instance, must called on a prepared AECM instance. we only support 80 or 160 sample blocks
     * of data.
     *
     * @param nearendNoisy
     *            - In buffer containing one frame of reference nearend+echo signal. If noise reduction is active,
     *            provide the noisy signal here.
     * @param nearendClean
     *            - In buffer containing one frame of nearend+echo signal. If noise reduction is active, provide the
     *            clean signal here. Otherwise pass a NULL pointer
     *            or just call {@link #echoCancellation(short[] nearendNoisy, int numOfSamples, int delay)}.
     * @param numOfSamples
     *            - Number of samples in nearend buffer. Must be <= Short.MAX_VALUE and >= Short.MIN_VALUE
     * @param delay
     *            - Delay estimate for sound card and system buffers <br>
     *            delay = (t_render - t_analyze) + (t_process - t_capture)<br>
     *            where<br>
     *            - t_analyze is the time a frame is passed to farendBuffer() and t_render is the time the first sample
     *            of the same frame is rendered by the audio hardware.<br>
     *            - t_capture is the time the first sample of a frame is captured by the audio hardware and t_process is
     *            the time the same frame is passed to echoCancellation(). Must be <= Short.MAX_VALUE and >= Short.MIN_VALUE
     *
     * @return one processed frame without echo or null if echoCancellation() is called on an unprepared AECM instance
     *         or you pass an invalid parameter.
     */
    public short[] echoCancellation(short[] nearendNoisy, short[] nearendClean, int numOfSamples, int delay) {
        // check if AECM instance is not initialized.
        if (!mIsInit) {
            Log.d(TAG, "echoCancellation() is called on an unprepared AECM instance or you pass an invalid parameter");
            return null;
        }

        if (numOfSamples > Short.MAX_VALUE) {
            Log.d(TAG, "echoCancellation() numOfSamples > Short.MAX_VALUE, Short.MAX_VALUE will be used instead");
            numOfSamples = Short.MAX_VALUE;
        } else if (numOfSamples < Short.MIN_VALUE) {
            Log.d(TAG, "echoCancellation() numOfSamples < Short.MIN_VALUE, Short.MIN_VALUE will be used instead");
            numOfSamples = Short.MIN_VALUE;
        }

        if (delay > Short.MAX_VALUE) {
            Log.d(TAG, "echoCancellation() delay > Short.MAX_VALUE, Short.MAX_VALUE will be used instead");
            delay = Short.MAX_VALUE;
        } else if (delay < Short.MIN_VALUE) {
            Log.d(TAG, "echoCancellation() delay < Short.MIN_VALUE, Short.MIN_VALUE will be used instead");
            delay = Short.MIN_VALUE;
        }

        return nativeAecmProcess(mAecmHandler, nearendNoisy, nearendClean, (short) numOfSamples, (short) delay);
    }

    /**
     * core process of AECM instance, must called on a prepared AECM instance. we only support 80 or 160 sample blocks
     * of data.
     *
     * @param nearendNoisy
     *            - In buffer containing one frame of reference nearend+echo signal. If noise reduction is active,
     *            provide the noisy signal here.
     * @param numOfSamples
     *            - Number of samples in nearend buffer. Must be <= Short.MAX_VALUE and >= Short.MIN_VALUE
     * @param delay
     *            - Delay estimate for sound card and system buffers <br>
     *            delay = (t_render - t_analyze) + (t_process - t_capture)<br>
     *            where<br>
     *            - t_analyze is the time a frame is passed to farendBuffer() and t_render is the time the first sample
     *            of the same frame is rendered by the audio hardware.<br>
     *            - t_capture is the time the first sample of a frame is captured by the audio hardware and t_process is
     *            the time the same frame is passed to echoCancellation(). Must be <= Short.MAX_VALUE and >= Short.MIN_VALUE
     *
     * @return one processed frame without echo or null if echoCancellation() is called on an unprepared AECM instance
     *         or you pass an invalid parameter.
     */
    public short[] echoCancellation(short[] nearendNoisy, int numOfSamples, int delay) {
        return echoCancellation(nearendNoisy, null, numOfSamples, delay);
    }

    public AEC setAecmMode(AggressiveMode mode) {
        return setAecmMode(mode, true);
    }

    /**
     * Set the aggressiveness mode of AECM instance, more higher the mode is, more aggressive the instance will be.
     * @param prepare - is flag that indicate will or will not prepare AECM instance after aggressiveness mode was set.
     * @param mode
     * @return the {@link AEC AEC} object itself or null if mode is null.
     */
    private AEC setAecmMode(AggressiveMode mode, boolean prepare) {
        // check the mode argument.
        if (mode == null) {
            Log.d(TAG, "setAecMode() mode == null, AggressiveMode.AGGRESSIVE will be used instead");
            mode = AggressiveMode.AGGRESSIVE;
        }

        mAecmConfig.mAecmMode = (short) mode.getMode();

        return prepare ? prepare() : this;
    }

    /**
     * When finished the pre-works or any settings are changed, call this to make AECM instance prepared. Otherwise your
     * new settings will be ignored by the AECM instance.
     *
     * @return the {@link AEC AEC} object itself.
     */
    public AEC prepare() {
        if (mIsInit) {
            close();
            mAecmHandler = nativeCreateAecmInstance();
        }

        mInitAecmInstance(mSampFreq.getFS(), mAecmConfig.mAecmMode);
        mIsInit = true;

        // set AecConfig to native side.
        nativeSetConfig(mAecmHandler, mAecmConfig);

        Log.d(TAG, "AECM instance successfully prepared with sampling frequency: " + mSampFreq.getFS() + "hz " + "and aggressiveness mode: " + mAecmConfig.mAecmMode);

        return this;
    }

    /**
     * Release the resources in AECM instance and the AECM instance is no longer available until next <b>prepare()</b>
     * is called.<br>
     * You should <b>always</b> call this <b>manually</b> when all things are done.
     */
    public void close() {
        if (mIsInit) {
            nativeFreeAecmInstance(mAecmHandler);
            mAecmHandler = -1;
            mIsInit = false;
        }
    }

    // ////////////////////////////////////////////////////////
    // PROTECTED METHODS

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if (mIsInit) {
            close();
        }
    }

    // ////////////////////////////////////////////////////////
    // PRIVATE METHODS

    /**
     * initialize the AECM instance
     *
     * @param sampFreq
     * @param mAecmMode
     */
    private void mInitAecmInstance(int sampFreq, short mAecmMode) {
        if (!mIsInit) {
            nativeInitializeAecmInstance(mAecmHandler, sampFreq);

            // initialize configurations of AECM instance.
            mAecmConfig = new AecmConfig();
            mAecmConfig.mAecmMode = mAecmMode;

            // set default configuration of AECM instance
            nativeSetConfig(mAecmHandler, mAecmConfig);

            mIsInit = true;
        }
    }

    // ////////////////////////////////////////////////////////
    // PRIVATE NESTED CLASSES

    /**
     * Acoustic Echo Cancellation for Mobile Configuration class, holds the config Info. of AECM instance.<br>
     * [NOTE] <b>DO NOT</b> modify the name of members, or you must change the native code to match your modifying.
     * Otherwise the native code could not find pre-binding members name.<br>
     *
     */
    @SuppressWarnings("unused")
    public class AecmConfig {
        private short mAecmMode = (short) AggressiveMode.AGGRESSIVE.getMode(); // default AggressiveMode.AGGRESSIVE
        private short mCngMode  = AECM_ENABLE;                                // AECM_UNABLE, AECM_ENABLE (default)
    }

    // ///////////////////////////////////////////
    // PRIVATE NATIVE INTERFACES

    /**
     * Allocates the memory needed by the AECM. The memory needs to be initialized separately using the
     * nativeInitializeAecmInstance() method.
     *
     * @return -1: error<br>
     *         other values: created AECM instance handler.
     *
     */
    private static native long nativeCreateAecmInstance();

    /**
     * Release the memory allocated by nativeCreateAecmInstance().
     *
     * @param aecmHandler
     *            - handler of the AECM instance created by nativeCreateAecmInstance()
     * @return 0: OK<br>
     *         -1: error
     */
    private static native int nativeFreeAecmInstance(long aecmHandler);

    /**
     * Initializes an AECM instance.
     *
     * @param aecmHandler
     *            - Handler of AECM instance
     * @param samplingFrequency
     *            - Sampling frequency of data
     * @return: 0: OK<br>
     *          -1: error
     */
    private static native int nativeInitializeAecmInstance(long aecmHandler, int samplingFrequency);

    /**
     * Inserts an 80 or 160 sample block of data into the farend buffer.
     *
     * @param aecmHandler
     *            - Handler to the AECM instance
     * @param farend
     *            - In buffer containing one frame of farend signal for L band
     * @param nrOfSamples
     *            - Number of samples in farend buffer
     * @return: 0: OK<br>
     *          -1: error
     */
    private static native int nativeBufferFarend(long aecmHandler, short[] farend, int nrOfSamples);

    /**
     * Runs the AECM on an 80 or 160 sample blocks of data.
     *
     * @param aecmHandler
     *            - Handler to the AECM handler
     * @param nearendNoisy
     *            - In buffer containing one frame of reference nearend+echo signal. If noise reduction is active,
     *            provide the noisy signal here.
     * @param nearendClean
     *            - In buffer containing one frame of nearend+echo signal. If noise reduction is active, provide the
     *            clean signal here.Otherwise pass a NULL pointer.
     * @param nrOfSamples
     *            - Number of samples in nearend buffer
     * @param msInSndCardBuf
     *            - Delay estimate for sound card and system buffers <br>
     * @return short array: OK: processed shorts<br>
     *          null: error
     */
    private static native short[] nativeAecmProcess(long aecmHandler, short[] nearendNoisy, short[] nearendClean, short nrOfSamples, short msInSndCardBuf);

    /**
     * Enables the user to set certain parameters on-the-fly.
     *
     * @param aecmHandler
     *            - Handler to the AECM instance
     * @param aecmConfig
     *            - the new configuration of AECM instance to set.
     *
     * @return 0: OK<br>
     *         -1: error
     */
    private static native int nativeSetConfig(long aecmHandler, AecmConfig aecmConfig);
}
