/*
 * Copyright (C) 2012-2017 Tobias Brunner
 * Copyright (C) secunet Security Networks AG
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version. See <http://www.gnu.org/copyleft/gpl.txt>.
 *
 * Stripped for SwiftVPN: IMC/BYOD and the strongSwan UI are gone. The retry
 * engine, listener plumbing and CharonVpnService-facing API are unchanged.
 * An optional static reporter forwards every state change to the host app.
 */

package org.strongswan.android.logic;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;

import org.strongswan.android.R;
import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.data.VpnProfileDataSource;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.concurrent.Callable;

import androidx.core.content.ContextCompat;

public class VpnStateService extends Service
{
	private final HashSet<VpnStateListener> mListeners = new HashSet<VpnStateListener>();
	private final IBinder mBinder = new LocalBinder();
	private long mConnectionID = 0;
	private Handler mHandler;
	private VpnProfile mProfile;
	private State mState = State.DISABLED;
	private ErrorState mError = ErrorState.NO_ERROR;
	private static final long RETRY_INTERVAL = 1000;
	/* cap the retry interval at 2 minutes */
	private static final long MAX_RETRY_INTERVAL = 120000;
	private static final int RETRY_MSG = 1;
	private final RetryTimeoutProvider mTimeoutProvider = new RetryTimeoutProvider();
	private long mRetryTimeout;
	private long mRetryIn;

	/** Host-app hook: invoked on the main thread after every state change. */
	public interface StateReporter
	{
		void onState(State state, ErrorState error, VpnProfile profile);
	}

	private static volatile StateReporter sReporter;

	public static void setStateReporter(StateReporter reporter)
	{
		sReporter = reporter;
	}

	public enum State
	{
		DISABLED,
		CONNECTING,
		CONNECTED,
		DISCONNECTING,
	}

	public enum ErrorState
	{
		NO_ERROR,
		AUTH_FAILED,
		PEER_AUTH_FAILED,
		LOOKUP_FAILED,
		UNREACHABLE,
		GENERIC_ERROR,
		PASSWORD_MISSING,
		CERTIFICATE_UNAVAILABLE,
	}

	public interface VpnStateListener
	{
		void stateChanged();
	}

	public class LocalBinder extends Binder
	{
		public VpnStateService getService()
		{
			return VpnStateService.this;
		}
	}

	@Override
	public void onCreate()
	{
		mHandler = new RetryHandler(getMainLooper(), this);
	}

	@Override
	public IBinder onBind(Intent intent)
	{
		return mBinder;
	}

	public void registerListener(VpnStateListener listener)
	{
		mListeners.add(listener);
	}

	public void unregisterListener(VpnStateListener listener)
	{
		mListeners.remove(listener);
	}

	public VpnProfile getProfile()
	{
		return mProfile;
	}

	public long getConnectionID()
	{
		return mConnectionID;
	}

	public int getRetryTimeout()
	{
		return (int)(mRetryTimeout / 1000);
	}

	public int getRetryIn()
	{
		return (int)(mRetryIn / 1000);
	}

	public State getState()
	{
		return mState;
	}

	public ErrorState getErrorState()
	{
		return mError;
	}

	public int getErrorText()
	{
		switch (mError)
		{
			case AUTH_FAILED:
				return R.string.error_auth_failed;
			case PEER_AUTH_FAILED:
				return R.string.error_peer_auth_failed;
			case LOOKUP_FAILED:
				return R.string.error_lookup_failed;
			case UNREACHABLE:
				return R.string.error_unreachable;
			case PASSWORD_MISSING:
				return R.string.error_password_missing;
			case CERTIFICATE_UNAVAILABLE:
				return R.string.error_certificate_unavailable;
			default:
				return R.string.error_generic;
		}
	}

	/**
	 * Disconnect any existing connection and shutdown the daemon.
	 */
	public void disconnect()
	{
		resetRetryTimer();
		setError(ErrorState.NO_ERROR);

		Context context = getApplicationContext();
		Intent intent = new Intent(context, CharonVpnService.class);
		intent.setAction(CharonVpnService.DISCONNECT_ACTION);
		context.startService(intent);
	}

	/**
	 * Connect (or reconnect) a profile.
	 *
	 * @param profileInfo optional profile info (UUID and password), previous profile if null
	 * @param fromScratch true if this is a manual retry/reconnect or a new connection
	 */
	public void connect(Bundle profileInfo, boolean fromScratch)
	{
		Context context = getApplicationContext();
		Intent intent = new Intent(context, CharonVpnService.class);
		if (profileInfo == null)
		{
			profileInfo = new Bundle();
			profileInfo.putString(VpnProfileDataSource.KEY_UUID, mProfile.getUUID().toString());
			profileInfo.putString(VpnProfileDataSource.KEY_PASSWORD, mProfile.getPassword());
		}
		if (fromScratch)
		{
			mTimeoutProvider.reset();
		}
		else
		{	/* mark this as an automatic retry */
			profileInfo.putBoolean(CharonVpnService.KEY_IS_RETRY, true);
		}
		intent.putExtras(profileInfo);
		ContextCompat.startForegroundService(context, intent);
	}

	public void reconnect()
	{
		if (mProfile == null)
		{
			return;
		}
		connect(null, true);
	}

	private void notifyListeners(final Callable<Boolean> change)
	{
		mHandler.post(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					if (change.call())
					{
						for (VpnStateListener listener : mListeners)
						{
							listener.stateChanged();
						}
						StateReporter reporter = sReporter;
						if (reporter != null)
						{
							reporter.onState(mState, mError, mProfile);
						}
					}
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}
			}
		});
	}

	public void startConnection(final VpnProfile profile)
	{
		notifyListeners(new Callable<Boolean>()
		{
			@Override
			public Boolean call()
			{
				resetRetryTimer();
				mConnectionID++;
				mProfile = profile;
				mState = State.CONNECTING;
				mError = ErrorState.NO_ERROR;
				return true;
			}
		});
	}

	public void setState(final State state)
	{
		notifyListeners(new Callable<Boolean>()
		{
			@Override
			public Boolean call()
			{
				if (state == State.CONNECTED)
				{	/* reset counter in case there is an error later on */
					mTimeoutProvider.reset();
				}
				if (mState != state)
				{
					mState = state;
					return true;
				}
				return false;
			}
		});
	}

	public void setError(final ErrorState error)
	{
		notifyListeners(new Callable<Boolean>()
		{
			@Override
			public Boolean call()
			{
				if (mError != error)
				{
					if (mError == ErrorState.NO_ERROR)
					{
						setRetryTimer(error);
					}
					else if (error == ErrorState.NO_ERROR)
					{
						resetRetryTimer();
					}
					mError = error;
					return true;
				}
				return false;
			}
		});
	}

	private void setRetryTimer(ErrorState error)
	{
		mRetryTimeout = mRetryIn = mTimeoutProvider.getTimeout(error);
		if (mRetryTimeout <= 0)
		{
			return;
		}
		mHandler.sendMessageAtTime(mHandler.obtainMessage(RETRY_MSG), SystemClock.uptimeMillis() + RETRY_INTERVAL);
	}

	private void resetRetryTimer()
	{
		mRetryTimeout = 0;
		mRetryIn = 0;
	}

	private static class RetryHandler extends Handler
	{
		WeakReference<VpnStateService> mService;

		public RetryHandler(Looper looper, VpnStateService service)
		{
			super(looper);
			mService = new WeakReference<>(service);
		}

		@Override
		public void handleMessage(Message msg)
		{
			VpnStateService service = mService.get();
			if (service == null || service.mRetryTimeout <= 0)
			{
				return;
			}
			service.mRetryIn -= RETRY_INTERVAL;
			if (service.mRetryIn > 0)
			{
				long next = SystemClock.uptimeMillis() + RETRY_INTERVAL;
				for (VpnStateListener listener : service.mListeners)
				{
					listener.stateChanged();
				}
				sendMessageAtTime(obtainMessage(RETRY_MSG), next);
			}
			else
			{
				service.connect(null, false);
			}
		}
	}

	/** Exponential backoff for automatic retries. */
	private static class RetryTimeoutProvider
	{
		private long mRetry;

		private long getBaseTimeout(ErrorState error)
		{
			switch (error)
			{
				case AUTH_FAILED:
					return 10000;
				case PEER_AUTH_FAILED:
				case LOOKUP_FAILED:
				case UNREACHABLE:
				case CERTIFICATE_UNAVAILABLE:
					return 5000;
				case PASSWORD_MISSING:
					/* needs user intervention */
					return 0;
				default:
					return 10000;
			}
		}

		public long getTimeout(ErrorState error)
		{
			/* SwiftVPN: automatic retries are disabled. A retry fires a fresh
			 * CharonVpnService start, and establishing a new VpnService session
			 * kills whichever tunnel is currently active (OpenVPN/WireGuard/
			 * Xray) — the engine owns reconnect UX, not the service. */
			return 0;
		}

		public void reset()
		{
			mRetry = 0;
		}
	}
}
