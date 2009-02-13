/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewRoot;

import com.android.internal.os.HandlerCaller;
import com.android.internal.view.IInputConnectionWrapper;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodCallback;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputBindResult;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Central system API to the overall input method framework (IMF) architecture,
 * which arbitrates interaction between applications and the current input method.
 * You can retrieve an instance of this interface with
 * {@link Context#getSystemService(String) Context.getSystemService()}.
 * 
 * <p>Topics covered here:
 * <ol>
 * <li><a href="#ArchitectureOverview">Architecture Overview</a>
 * </ol>
 * 
 * <a name="ArchitectureOverview"></a>
 * <h3>Architecture Overview</h3>
 * 
 * <p>There are three primary parties involved in the input method
 * framework (IMF) architecture:</p>
 * 
 * <ul>
 * <li> The <strong>input method manager</strong> as expressed by this class
 * is the central point of the system that manages interaction between all
 * other parts.  It is expressed as the client-side API here which exists
 * in each application context and communicates with a global system service
 * that manages the interaction across all processes.
 * <li> An <strong>input method (IME)</strong> implements a particular
 * interaction model allowing the user to generate text.  The system binds
 * to the current input method that is use, causing it to be created and run,
 * and tells it when to hide and show its UI.  Only one IME is running at a time.
 * <li> Multiple <strong>client applications</strong> arbitrate with the input
 * method manager for input focus and control over the state of the IME.  Only
 * one such client is ever active (working with the IME) at a time.
 * </ul>
 * 
 * 
 * <a name="Applications"></a>
 * <h3>Applications</h3>
 * 
 * <p>In most cases, applications that are using the standard
 * {@link android.widget.TextView} or its subclasses will have little they need
 * to do to work well with soft input methods.  The main things you need to
 * be aware of are:</p>
 * 
 * <ul>
 * <li> Properly set the {@link android.R.attr#inputType} if your editable
 * text views, so that the input method will have enough context to help the
 * user in entering text into them.
 * <li> Deal well with losing screen space when the input method is
 * displayed.  Ideally an application should handle its window being resized
 * smaller, but it can rely on the system performing panning of the window
 * if needed.  You should set the {@link android.R.attr#windowSoftInputMode}
 * attribute on your activity or the corresponding values on windows you
 * create to help the system determine whether to pan or resize (it will
 * try to determine this automatically but may get it wrong).
 * <li> You can also control the preferred soft input state (open, closed, etc)
 * for your window using the same {@link android.R.attr#windowSoftInputMode}
 * attribute.
 * </ul>
 * 
 * <p>More finer-grained control is available through the APIs here to directly
 * interact with the IMF and its IME -- either showing or hiding the input
 * area, letting the user pick an input method, etc.</p>
 * 
 * <p>For the rare people amongst us writing their own text editors, you
 * will need to implement {@link android.view.View#onCreateInputConnection}
 * to return a new instance of your own {@link InputConnection} interface
 * allowing the IME to interact with your editor.</p>
 * 
 * 
 * <a name="InputMethods"></a>
 * <h3>Input Methods</h3>
 * 
 * <p>An input method (IME) is implemented
 * as a {@link android.app.Service}, typically deriving from
 * {@link android.inputmethodservice.InputMethodService}.  It must provide
 * the core {@link InputMethod} interface, though this is normally handled by
 * {@link android.inputmethodservice.InputMethodService} and implementors will
 * only need to deal with the higher-level API there.</p>
 * 
 * See the {@link android.inputmethodservice.InputMethodService} class for
 * more information on implementing IMEs.
 * 
 * 
 * <a name="Security"></a>
 * <h3>Security</h3>
 * 
 * <p>There are a lot of security issues associated with input methods,
 * since they essentially have freedom to completely drive the UI and monitor
 * everything the user enters.  The Android input method framework also allows
 * arbitrary third party IMEs, so care must be taken to restrict their
 * selection and interactions.</p>
 * 
 * <p>Here are some key points about the security architecture behind the
 * IMF:</p>
 * 
 * <ul>
 * <li> <p>Only the system is allowed to directly access an IME's
 * {@link InputMethod} interface, via the
 * {@link android.Manifest.permission#BIND_INPUT_METHOD} permission.  This is
 * enforced in the system by not binding to an input method service that does
 * not require this permission, so the system can guarantee no other untrusted
 * clients are accessing the current input method outside of its control.</p>
 * 
 * <li> <p>There may be many client processes of the IMF, but only one may
 * be active at a time.  The inactive clients can not interact with key
 * parts of the IMF through the mechanisms described below.</p>
 * 
 * <li> <p>Clients of an input method are only given access to its
 * {@link InputMethodSession} interface.  One instance of this interface is
 * created for each client, and only calls from the session associated with
 * the active client will be processed by the current IME.  This is enforced
 * by {@link android.inputmethodservice.AbstractInputMethodService} for normal
 * IMEs, but must be explicitly handled by an IME that is customizing the
 * raw {@link InputMethodSession} implementation.</p>
 * 
 * <li> <p>Only the active client's {@link InputConnection} will accept
 * operations.  The IMF tells each client process whether it is active, and
 * the framework enforces that in inactive processes calls on to the current
 * InputConnection will be ignored.  This ensures that the current IME can
 * only deliver events and text edits to the UI that the user sees as
 * being in focus.</p>
 * 
 * <li> <p>An IME can never interact with an {@link InputConnection} while
 * the screen is off.  This is enforced by making all clients inactive while
 * the screen is off, and prevents bad IMEs from driving the UI when the user
 * can not be aware of its behavior.</p>
 * 
 * <li> <p>A client application can ask that the system let the user pick a
 * new IME, but can not programmatically switch to one itself.  This avoids
 * malicious applications from switching the user to their own IME, which
 * remains running when the user navigates away to another application.  An
 * IME, on the other hand, <em>is</em> allowed to programmatically switch
 * the system to another IME, since it already has full control of user
 * input.</p>
 * 
 * <li> <p>The user must explicitly enable a new IME in settings before
 * they can switch to it, to confirm with the system that they know about it
 * and want to make it available for use.</p>
 * </ul>
 */
public final class InputMethodManager {
    static final boolean DEBUG = false;
    static final String TAG = "InputMethodManager";

    /**
     * The package name of the build-in input method.
     * {@hide}
     */
    public static final String BUILDIN_INPUTMETHOD_PACKAGE = "android.text.inputmethod";

    static final Object mInstanceSync = new Object();
    static InputMethodManager mInstance;
    
    final IInputMethodManager mService;
    final Looper mMainLooper;
    
    // For scheduling work on the main thread.  This also serves as our
    // global lock.
    final H mH;
    
    // Our generic input connection if the current target does not have its own.
    final IInputContext mIInputContext;

    /**
     * True if this input method client is active, initially false.
     */
    boolean mActive = false;
    
    /**
     * As reported by IME through InputConnection.
     */
    boolean mFullscreenMode;
    
    // -----------------------------------------------------------
    
    /**
     * This is the view that should currently be served by an input method,
     * regardless of the state of setting that up.
     */
    View mServedView;
    /**
     * For evaluating the state after a focus change, this is the view that
     * had focus.
     */
    View mLastServedView;
    /**
     * This is set when we are in the process of connecting, to determine
     * when we have actually finished.
     */
    boolean mServedConnecting;
    /**
     * This is non-null when we have connected the served view; it holds
     * the attributes that were last retrieved from the served view and given
     * to the input connection.
     */
    EditorInfo mCurrentTextBoxAttribute;
    /**
     * The InputConnection that was last retrieved from the served view.
     */
    InputConnection mServedInputConnection;
    /**
     * The completions that were last provided by the served view.
     */
    CompletionInfo[] mCompletions;
    
    // Cursor position on the screen.
    Rect mTmpCursorRect = new Rect();
    Rect mCursorRect = new Rect();
    int mCursorSelStart;
    int mCursorSelEnd;
    int mCursorCandStart;
    int mCursorCandEnd;

    // -----------------------------------------------------------
    
    /**
     * Sequence number of this binding, as returned by the server.
     */
    int mBindSequence = -1;
    /**
     * ID of the method we are bound to.
     */
    String mCurId;
    /**
     * The actual instance of the method to make calls on it.
     */
    IInputMethodSession mCurMethod;

    // -----------------------------------------------------------
    
    static final int MSG_DUMP = 1;
    
    class H extends Handler {
        H(Looper looper) {
            super(looper);
        }
        
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DUMP: {
                    HandlerCaller.SomeArgs args = (HandlerCaller.SomeArgs)msg.obj;
                    try {
                        doDump((FileDescriptor)args.arg1,
                                (PrintWriter)args.arg2, (String[])args.arg3);
                    } catch (RuntimeException e) {
                        ((PrintWriter)args.arg2).println("Exception: " + e);
                    }
                    synchronized (args.arg4) {
                        ((CountDownLatch)args.arg4).countDown();
                    }
                    return;
                }
            }
        }
    }
    
    class ControlledInputConnectionWrapper extends IInputConnectionWrapper {
        public ControlledInputConnectionWrapper(Looper mainLooper, InputConnection conn) {
            super(mainLooper, conn);
        }

        public boolean isActive() {
            return mActive;
        }
    }
    
    final IInputMethodClient.Stub mClient = new IInputMethodClient.Stub() {
        @Override protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
            // No need to check for dump permission, since we only give this
            // interface to the system.
            
            CountDownLatch latch = new CountDownLatch(1);
            HandlerCaller.SomeArgs sargs = new HandlerCaller.SomeArgs();
            sargs.arg1 = fd;
            sargs.arg2 = fout;
            sargs.arg3 = args;
            sargs.arg4 = latch;
            mH.sendMessage(mH.obtainMessage(MSG_DUMP, sargs));
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    fout.println("Timeout waiting for dump");
                }
            } catch (InterruptedException e) {
                fout.println("Interrupted waiting for dump");
            }
        }
        
        public void setUsingInputMethod(boolean state) {
        }
        
        public void onBindMethod(InputBindResult res) {
            synchronized (mH) {
                if (mBindSequence < 0 || mBindSequence != res.sequence) {
                    Log.w(TAG, "Ignoring onBind: cur seq=" + mBindSequence
                            + ", given seq=" + res.sequence);
                    return;
                }
                
                mCurMethod = res.method;
                mCurId = res.id;
                mBindSequence = res.sequence;
            }
            startInputInner();
        }
        
        public void onUnbindMethod(int sequence) {
            synchronized (mH) {
                if (mBindSequence == sequence) {
                    if (false) {
                        // XXX the server has already unbound!
                        if (mCurMethod != null && mCurrentTextBoxAttribute != null) {
                            try {
                                mCurMethod.finishInput();
                            } catch (RemoteException e) {
                                Log.w(TAG, "IME died: " + mCurId, e);
                            }
                        }
                    }
                    clearBindingLocked();
                    
                    // If we were actively using the last input method, then
                    // we would like to re-connect to the next input method.
                    if (mServedView != null && mServedView.isFocused()) {
                        mServedConnecting = true;
                    }
                }
                startInputInner();
            }
        }
        
        public void setActive(boolean active) {
            mActive = active;
            mFullscreenMode = false;
        }
    };    
    
    final InputConnection mDummyInputConnection = new BaseInputConnection(this, true);
    
    InputMethodManager(IInputMethodManager service, Looper looper) {
        mService = service;
        mMainLooper = looper;
        mH = new H(looper);
        mIInputContext = new ControlledInputConnectionWrapper(looper,
                mDummyInputConnection);
        
        if (mInstance == null) {
            mInstance = this;
        }
    }

    /**
     * Retrieve the global InputMethodManager instance, creating it if it
     * doesn't already exist.
     * @hide
     */
    static public InputMethodManager getInstance(Context context) {
        synchronized (mInstanceSync) {
            if (mInstance != null) {
                return mInstance;
            }
            IBinder b = ServiceManager.getService(Context.INPUT_METHOD_SERVICE);
            IInputMethodManager service = IInputMethodManager.Stub.asInterface(b);
            mInstance = new InputMethodManager(service, context.getMainLooper());
        }
        return mInstance;
    }
    
    /**
     * Private optimization: retrieve the global InputMethodManager instance,
     * if it exists.
     * @hide
     */
    static public InputMethodManager peekInstance() {
        return mInstance;
    }
    
    /** @hide */
    public IInputMethodClient getClient() {
        return mClient;
    }
    
    /** @hide */
    public IInputContext getInputContext() {
        return mIInputContext;
    }
    
    public List<InputMethodInfo> getInputMethodList() {
        try {
            return mService.getInputMethodList();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public List<InputMethodInfo> getEnabledInputMethodList() {
        try {
            return mService.getEnabledInputMethodList();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public void showStatusIcon(IBinder imeToken, String packageName, int iconId) {
        try {
            mService.updateStatusIcon(imeToken, packageName, iconId);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public void hideStatusIcon(IBinder imeToken) {
        try {
            mService.updateStatusIcon(imeToken, null, 0);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /** @hide */
    public void setFullscreenMode(boolean enabled) {
        mFullscreenMode = true;
    }
    
    /**
     * Allows you to discover whether the attached input method is running
     * in fullscreen mode.  Return true if it is fullscreen, entirely covering
     * your UI, else returns false.
     */
    public boolean isFullscreenMode() {
        return mFullscreenMode;
    }
    
    /**
     * Return true if the given view is the currently active view for the
     * input method.
     */
    public boolean isActive(View view) {
        synchronized (mH) {
            return mServedView == view && mCurrentTextBoxAttribute != null;
        }
    }
    
    /**
     * Return true if any view is currently active in the input method.
     */
    public boolean isActive() {
        synchronized (mH) {
            return mServedView != null && mCurrentTextBoxAttribute != null;
        }
    }
    
    /**
     * Return true if the currently served view is accepting full text edits.
     * If false, it has no input connection, so can only handle raw key events.
     */
    public boolean isAcceptingText() {
        return mServedInputConnection != null;
    }

    /**
     * Reset all of the state associated with being bound to an input method.
     */
    void clearBindingLocked() {
        clearConnectionLocked();
        mBindSequence = -1;
        mCurId = null;
        mCurMethod = null;
    }
    
    /**
     * Reset all of the state associated with a served view being connected
     * to an input method
     */
    void clearConnectionLocked() {
        mCurrentTextBoxAttribute = null;
        mServedInputConnection = null;
    }
    
    /**
     * Disconnect any existing input connection, clearing the served view.
     */
    void finishInputLocked() {
        if (mServedView != null) {
            if (DEBUG) Log.v(TAG, "FINISH INPUT: " + mServedView);
            
            if (mCurrentTextBoxAttribute != null) {
                try {
                    mService.finishInput(mClient);
                } catch (RemoteException e) {
                }
            }
            
            if (mServedInputConnection != null) {
                // We need to tell the previously served view that it is no
                // longer the input target, so it can reset its state.  Schedule
                // this call on its window's Handler so it will be on the correct
                // thread and outside of our lock.
                Handler vh = mServedView.getHandler();
                if (vh != null) {
                    // This will result in a call to reportFinishInputConnection()
                    // below.
                    vh.sendMessage(vh.obtainMessage(ViewRoot.FINISH_INPUT_CONNECTION,
                            mServedInputConnection));
                }
            }
            
            mServedView = null;
            mCompletions = null;
            mServedConnecting = false;
            clearConnectionLocked();
        }
    }
    
    /**
     * Called from the FINISH_INPUT_CONNECTION message above.
     * @hide
     */
    public void reportFinishInputConnection(InputConnection ic) {
        if (mServedInputConnection != ic) {
            ic.finishComposingText();
        }
    }
    
    public void displayCompletions(View view, CompletionInfo[] completions) {
        synchronized (mH) {
            if (mServedView != view) {
                return;
            }
            
            mCompletions = completions;
            if (mCurMethod != null) {
                try {
                    mCurMethod.displayCompletions(mCompletions);
                } catch (RemoteException e) {
                }
            }
        }
    }
    
    public void updateExtractedText(View view, int token, ExtractedText text) {
        synchronized (mH) {
            if (mServedView != view) {
                return;
            }
            
            if (mCurMethod != null) {
                try {
                    mCurMethod.updateExtractedText(token, text);
                } catch (RemoteException e) {
                }
            }
        }
    }
    
    /**
     * Flag for {@link #showSoftInput} to indicate that this is an implicit
     * request to show the input window, not as the result of a direct request
     * by the user.  The window may not be shown in this case.
     */
    public static final int SHOW_IMPLICIT = 0x0001;
    
    /**
     * Flag for {@link #showSoftInput} to indicate that the user has forced
     * the input method open (such as by long-pressing menu) so it should
     * not be closed until they explicitly do so.
     */
    public static final int SHOW_FORCED = 0x0002;
    
    /**
     * Explicitly request that the current input method's soft input area be
     * shown to the user, if needed.  Call this if the user interacts with
     * your view in such a way that they have expressed they would like to
     * start performing input into it.
     * 
     * @param view The currently focused view, which would like to receive
     * soft keyboard input.
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link #SHOW_IMPLICIT} bit set.
     */
    public void showSoftInput(View view, int flags) {
        synchronized (mH) {
            if (mServedView != view) {
                return;
            }

            try {
                mService.showSoftInput(mClient, flags);
            } catch (RemoteException e) {
            }
        }
    }
    
    /** @hide */
    public void showSoftInputUnchecked(int flags) {
        try {
            mService.showSoftInput(mClient, flags);
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Flag for {@link #hideSoftInputFromWindow} to indicate that the soft
     * input window should only be hidden if it was not explicitly shown
     * by the user.
     */
    public static final int HIDE_IMPLICIT_ONLY = 0x0001;
    
    /**
     * Flag for {@link #hideSoftInputFromWindow} to indicate that the soft
     * input window should normally be hidden, unless it was originally
     * shown with {@link #SHOW_FORCED}.
     */
    public static final int HIDE_NOT_ALWAYS = 0x0002;
    
    /**
     * Request to hide the soft input window from the context of the window
     * that is currently accepting input.  This should be called as a result
     * of the user doing some actually than fairly explicitly requests to
     * have the input window hidden.
     * 
     * @param windowToken The token of the window that is making the request,
     * as returned by {@link View#getWindowToken() View.getWindowToken()}.
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link #HIDE_IMPLICIT_ONLY} bit set.
     */
    public void hideSoftInputFromWindow(IBinder windowToken, int flags) {
        synchronized (mH) {
            if (mServedView == null || mServedView.getWindowToken() != windowToken) {
                return;
            }

            try {
                mService.hideSoftInput(mClient, flags);
            } catch (RemoteException e) {
            }
        }
    }
    
    /**
     * If the input method is currently connected to the given view,
     * restart it with its new contents.  You should call this when the text
     * within your view changes outside of the normal input method or key
     * input flow, such as when an application calls TextView.setText().
     * 
     * @param view The view whose text has changed.
     */
    public void restartInput(View view) {
        synchronized (mH) {
            if (mServedView != view) {
                return;
            }
            
            mServedConnecting = true;
        }
        
        startInputInner();
    }
    
    void startInputInner() {
        final View view;
        synchronized (mH) {
            view = mServedView;
            
            // Make sure we have a window token for the served view.
            if (DEBUG) Log.v(TAG, "Starting input: view=" + view);
            if (view == null) {
                if (DEBUG) Log.v(TAG, "ABORT input: no served view!");
                return;
            }
        }
        
        // Now we need to get an input connection from the served view.
        // This is complicated in a couple ways: we can't be holding our lock
        // when calling out to the view, and we need to make sure we call into
        // the view on the same thread that is driving its view hierarchy.
        Handler vh = view.getHandler();
        if (vh == null) {
            // If the view doesn't have a handler, something has changed out
            // from under us, so just bail.
            if (DEBUG) Log.v(TAG, "ABORT input: no handler for view!");
            return;
        }
        if (vh.getLooper() != Looper.myLooper()) {
            // The view is running on a different thread than our own, so
            // we need to reschedule our work for over there.
            if (DEBUG) Log.v(TAG, "Starting input: reschedule to view thread");
            vh.post(new Runnable() {
                public void run() {
                    startInputInner();
                }
            });
        }
        
        // Okay we are now ready to call into the served view and have it
        // do its stuff.
        // Life is good: let's hook everything up!
        EditorInfo tba = new EditorInfo();
        tba.packageName = view.getContext().getPackageName();
        tba.fieldId = view.getId();
        InputConnection ic = view.onCreateInputConnection(tba);
        if (DEBUG) Log.v(TAG, "Starting input: tba=" + tba + " ic=" + ic);
        
        synchronized (mH) {
            // Now that we are locked again, validate that our state hasn't
            // changed.
            if (mServedView != view || !mServedConnecting) {
                // Something else happened, so abort.
                if (DEBUG) Log.v(TAG, 
                        "Starting input: finished by someone else (view="
                        + mServedView + " conn=" + mServedConnecting + ")");
                return;
            }
            
            // If we already have a text box, then this view is already
            // connected so we want to restart it.
            final boolean initial = mCurrentTextBoxAttribute == null;
            
            // Hook 'em up and let 'er rip.
            mCurrentTextBoxAttribute = tba;
            mServedConnecting = false;
            mServedInputConnection = ic;
            IInputContext servedContext;
            if (ic != null) {
                mCursorSelStart = tba.initialSelStart;
                mCursorSelEnd = tba.initialSelEnd;
                mCursorCandStart = -1;
                mCursorCandEnd = -1;
                mCursorRect.setEmpty();
                servedContext = new ControlledInputConnectionWrapper(vh.getLooper(), ic);
            } else {
                servedContext = null;
            }
            
            try {
                if (DEBUG) Log.v(TAG, "START INPUT: " + view + " ic="
                        + ic + " tba=" + tba + " initial=" + initial);
                InputBindResult res = mService.startInput(mClient,
                        servedContext, tba, initial, mCurMethod == null);
                if (DEBUG) Log.v(TAG, "Starting input: Bind result=" + res);
                if (res != null) {
                    if (res.id != null) {
                        mBindSequence = res.sequence;
                        mCurMethod = res.method;
                    } else {
                        // This means there is no input method available.
                        if (DEBUG) Log.v(TAG, "ABORT input: no input method!");
                        return;
                    }
                }
                if (mCurMethod != null && mCompletions != null) {
                    try {
                        mCurMethod.displayCompletions(mCompletions);
                    } catch (RemoteException e) {
                    }
                }
            } catch (RemoteException e) {
                Log.w(TAG, "IME died: " + mCurId, e);
            }
        }
    }

    /**
     * When the focused window is dismissed, this method is called to finish the
     * input method started before.
     * @hide
     */
    public void windowDismissed(IBinder appWindowToken) {
        synchronized (mH) {
            if (mServedView != null &&
                    mServedView.getWindowToken() == appWindowToken) {
                finishInputLocked();
            }
        }
    }

    /**
     * Call this when a view receives focus.
     * @hide
     */
    public void focusIn(View view) {
        synchronized (mH) {
            if (DEBUG) Log.v(TAG, "focusIn: " + view);
            // Okay we have a new view that is being served.
            if (mServedView != view) {
                mCurrentTextBoxAttribute = null;
            }
            mServedView = view;
            mCompletions = null;
            mServedConnecting = true;
        }
        
        startInputInner();
    }

    /**
     * Call this when a view loses focus.
     * @hide
     */
    public void focusOut(View view) {
        InputConnection ic = null;
        synchronized (mH) {
            if (DEBUG) Log.v(TAG, "focusOut: " + view
                    + " mServedView=" + mServedView
                    + " winFocus=" + view.hasWindowFocus());
            if (mServedView == view) {
                ic = mServedInputConnection;
                // The following code would auto-hide the IME if we end up
                // with no more views with focus.  This can happen, however,
                // whenever we go into touch mode, so it ends up hiding
                // at times when we don't really want it to.  For now it
                // seems better to just turn it all off.
                if (false && view.hasWindowFocus()) {
                    mLastServedView = view;
                    Handler vh = view.getHandler();
                    if (vh != null) {
                        // This will result in a call to checkFocus() below.
                        vh.removeMessages(ViewRoot.CHECK_FOCUS);
                        vh.sendMessage(vh.obtainMessage(ViewRoot.CHECK_FOCUS,
                                view));
                    }
                }
            }
        }
        
        if (ic != null) {
            ic.finishComposingText();
        }
    }

    /**
     * @hide
     */
    public void checkFocus(View view) {
        synchronized (mH) {
            if (view != mLastServedView) {
                return;
            }
            if (DEBUG) Log.v(TAG, "checkFocus: view=" + mServedView
                    + " last=" + mLastServedView);
            if (mServedView == mLastServedView) {
                finishInputLocked();
                // In this case, we used to have a focused view on the window,
                // but no longer do.  We should make sure the input method is
                // no longer shown, since it serves no purpose.
                closeCurrentInput();
            }
            mLastServedView = null;
        }
    }
    
    void closeCurrentInput() {
        try {
            mService.hideSoftInput(mClient, HIDE_NOT_ALWAYS);
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Called by ViewRoot the first time it gets window focus.
     * @hide
     */
    public void onWindowFocus(View focusedView, int softInputMode,
            boolean first, int windowFlags) {
        synchronized (mH) {
            if (DEBUG) Log.v(TAG, "onWindowFocus: " + focusedView
                    + " softInputMode=" + softInputMode
                    + " first=" + first + " flags=#"
                    + Integer.toHexString(windowFlags));
            try {
                final boolean isTextEditor = focusedView != null &&
                focusedView.onCheckIsTextEditor();
                mService.windowGainedFocus(mClient, focusedView != null,
                        isTextEditor, softInputMode, first, windowFlags);
            } catch (RemoteException e) {
            }
        }
    }
    
    /**
     * Report the current selection range.
     */
    public void updateSelection(View view, int selStart, int selEnd,
            int candidatesStart, int candidatesEnd) {
        synchronized (mH) {
            if (mServedView != view || mCurrentTextBoxAttribute == null
                    || mCurMethod == null) {
                return;
            }
            
            if (mCursorSelStart != selStart || mCursorSelEnd != selEnd
                    || mCursorCandStart != candidatesStart
                    || mCursorCandEnd != candidatesEnd) {
                if (DEBUG) Log.d(TAG, "updateSelection");

                try {
                    if (DEBUG) Log.v(TAG, "SELECTION CHANGE: " + mCurMethod);
                    mCurMethod.updateSelection(mCursorSelStart, mCursorSelEnd,
                            selStart, selEnd, candidatesStart, candidatesEnd);
                    mCursorSelStart = selStart;
                    mCursorSelEnd = selEnd;
                    mCursorCandStart = candidatesStart;
                    mCursorCandEnd = candidatesEnd;
                } catch (RemoteException e) {
                    Log.w(TAG, "IME died: " + mCurId, e);
                }
            }
        }
    }

    /**
     * Returns true if the current input method wants to watch the location
     * of the input editor's cursor in its window.
     */
    public boolean isWatchingCursor(View view) {
        return false;
    }
    
    /**
     * Report the current cursor location in its window.
     */
    public void updateCursor(View view, int left, int top, int right, int bottom) {
        synchronized (mH) {
            if (mServedView != view || mCurrentTextBoxAttribute == null
                    || mCurMethod == null) {
                return;
            }
            
            mTmpCursorRect.set(left, top, right, bottom);
            if (!mCursorRect.equals(mTmpCursorRect)) {
                if (DEBUG) Log.d(TAG, "updateCursor");

                try {
                    if (DEBUG) Log.v(TAG, "CURSOR CHANGE: " + mCurMethod);
                    mCurMethod.updateCursor(mTmpCursorRect);
                    mCursorRect.set(mTmpCursorRect);
                } catch (RemoteException e) {
                    Log.w(TAG, "IME died: " + mCurId, e);
                }
            }
        }
    }

    /**
     * Call {@link InputMethodSession#appPrivateCommand(String, Bundle)
     * InputMethodSession.appPrivateCommand()} on the current Input Method.
     * @param view Optional View that is sending the command, or null if
     * you want to send the command regardless of the view that is attached
     * to the input method.
     * @param action Name of the command to be performed.  This <em>must</em>
     * be a scoped name, i.e. prefixed with a package name you own, so that
     * different developers will not create conflicting commands.
     * @param data Any data to include with the command.
     */
    public void sendAppPrivateCommand(View view, String action, Bundle data) {
        synchronized (mH) {
            if ((view != null && mServedView != view)
                    || mCurrentTextBoxAttribute == null || mCurMethod == null) {
                return;
            }
            try {
                if (DEBUG) Log.v(TAG, "APP PRIVATE COMMAND " + action + ": " + data);
                mCurMethod.appPrivateCommand(action, data);
            } catch (RemoteException e) {
                Log.w(TAG, "IME died: " + mCurId, e);
            }
        }
    }
    
    /**
     * Force switch to a new input method component.  This can only be called
     * from the currently active input method, as validated by the given token.
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     * @param id The unique identifier for the new input method to be switched to.
     */
    public void setInputMethod(IBinder token, String id) {
        try {
            mService.setInputMethod(token, id);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Close/hide the input method's soft input area, so the user no longer
     * sees it or can interact with it.  This can only be called
     * from the currently active input method, as validated by the given token.
     * 
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link #HIDE_IMPLICIT_ONLY} bit set.
     */
    public void hideSoftInputFromInputMethod(IBinder token, int flags) {
        try {
            mService.hideMySoftInput(token, flags);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * @hide
     */
    public void dispatchKeyEvent(Context context, int seq, KeyEvent key,
            IInputMethodCallback callback) {
        synchronized (mH) {
            if (DEBUG) Log.d(TAG, "dispatchKeyEvent");
    
            if (mCurMethod == null) {
                try {
                    callback.finishedEvent(seq, false);
                } catch (RemoteException e) {
                }
                return;
            }
    
            if (key.getAction() == KeyEvent.ACTION_DOWN
                    && key.getKeyCode() == KeyEvent.KEYCODE_SYM) {
                showInputMethodPicker();
                try {
                    callback.finishedEvent(seq, true);
                } catch (RemoteException e) {
                }
                return;
            }
            try {
                if (DEBUG) Log.v(TAG, "DISPATCH KEY: " + mCurMethod);
                mCurMethod.dispatchKeyEvent(seq, key, callback);
            } catch (RemoteException e) {
                Log.w(TAG, "IME died: " + mCurId + " dropping: " + key, e);
                try {
                    callback.finishedEvent(seq, false);
                } catch (RemoteException ex) {
                }
            }
        }
    }

    /**
     * @hide
     */
    void dispatchTrackballEvent(Context context, int seq, MotionEvent motion,
            IInputMethodCallback callback) {
        synchronized (mH) {
            if (DEBUG) Log.d(TAG, "dispatchTrackballEvent");
    
            if (mCurMethod == null || mCurrentTextBoxAttribute == null) {
                try {
                    callback.finishedEvent(seq, false);
                } catch (RemoteException e) {
                }
                return;
            }
    
            try {
                if (DEBUG) Log.v(TAG, "DISPATCH TRACKBALL: " + mCurMethod);
                mCurMethod.dispatchTrackballEvent(seq, motion, callback);
            } catch (RemoteException e) {
                Log.w(TAG, "IME died: " + mCurId + " dropping trackball: " + motion, e);
                try {
                    callback.finishedEvent(seq, false);
                } catch (RemoteException ex) {
                }
            }
        }
    }
    
    public void showInputMethodPicker() {
        synchronized (mH) {
            try {
                mService.showInputMethodPickerFromClient(mClient);
            } catch (RemoteException e) {
                Log.w(TAG, "IME died: " + mCurId, e);
            }
        }
    }
    
    void doDump(FileDescriptor fd, PrintWriter fout, String[] args) {
        final Printer p = new PrintWriterPrinter(fout);
        p.println("Input method client state for " + this + ":");
        
        p.println("  mService=" + mService);
        p.println("  mMainLooper=" + mMainLooper);
        p.println("  mIInputContext=" + mIInputContext);
        p.println("  mActive=" + mActive
                + " mBindSequence=" + mBindSequence
                + " mCurId=" + mCurId);
        p.println("  mCurMethod=" + mCurMethod);
        p.println("  mServedView=" + mServedView);
        p.println("  mLastServedView=" + mLastServedView);
        p.println("  mServedConnecting=" + mServedConnecting);
        if (mCurrentTextBoxAttribute != null) {
            p.println("  mCurrentTextBoxAttribute:");
            mCurrentTextBoxAttribute.dump(p, "    ");
        } else {
            p.println("  mCurrentTextBoxAttribute: null");
        }
        p.println("  mServedInputConnection=" + mServedInputConnection);
        p.println("  mCompletions=" + mCompletions);
        p.println("  mCursorRect=" + mCursorRect);
        p.println("  mCursorSelStart=" + mCursorSelStart
                + " mCursorSelEnd=" + mCursorSelEnd
                + " mCursorCandStart=" + mCursorCandStart
                + " mCursorCandEnd=" + mCursorCandEnd);
    }
}