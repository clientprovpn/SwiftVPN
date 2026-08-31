/*
 * Host-app context holder for the vendored strongSwan code.
 *
 * The upstream tree routes every context lookup through StrongSwanApplication,
 * a custom Application subclass we do not ship. This holder is initialised by
 * the host before any IKEv2 code runs and provides the same application
 * context instead.
 */

package org.strongswan.android.logic;

import android.content.Context;

public final class SwiftContext
{
	private static volatile Context sContext;

	private SwiftContext()
	{
	}

	public static void init(Context context)
	{
		if (context != null)
		{
			sContext = context.getApplicationContext();
		}
	}

	public static Context get()
	{
		Context context = sContext;
		if (context == null)
		{
			throw new IllegalStateException("SwiftContext.init() was not called");
		}
		return context;
	}
}
