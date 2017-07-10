/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.timezone.updater;

import android.app.timezone.Callback;
import android.app.timezone.DistroFormatVersion;
import android.app.timezone.DistroRulesVersion;
import android.app.timezone.RulesManager;
import android.app.timezone.RulesState;
import android.app.timezone.RulesUpdaterContract;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.TimeZoneRulesDataContract;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import libcore.io.Streams;

/**
 * A broadcast receiver triggered by an
 * {@link RulesUpdaterContract#ACTION_TRIGGER_RULES_UPDATE_CHECK intent} from the system server in
 * response to the installation/replacement/uninstallation of a time zone data app.
 *
 * <p>The trigger intent contains a {@link RulesUpdaterContract#EXTRA_CHECK_TOKEN byte[] check
 * token} which must be returned to the system server {@link RulesManager} API via one of the
 * {@link RulesManager#requestInstall(ParcelFileDescriptor, byte[], Callback) install},
 * {@link RulesManager#requestUninstall(byte[], Callback)} or
 * {@link RulesManager#requestNothing(byte[], boolean)} methods.
 *
 * <p>The RulesCheckReceiver is responsible for handling the operation requested by the data app.
 * The data app makes its payload available via a {@link TimeZoneRulesDataContract specified}
 * {@link android.content.ContentProvider} with the URI {@link TimeZoneRulesDataContract#AUTHORITY}.
 *
 * <p>If the {@link TimeZoneRulesDataContract.Operation#TYPE operation type} is an
 * {@link TimeZoneRulesDataContract.Operation#TYPE_INSTALL install request}, then the time zone data
 * format {@link TimeZoneRulesDataContract.Operation#DISTRO_MAJOR_VERSION major version} and
 * {@link TimeZoneRulesDataContract.Operation#DISTRO_MINOR_VERSION minor version}, the
 * {@link TimeZoneRulesDataContract.Operation#RULES_VERSION IANA rules version}, and the
 * {@link TimeZoneRulesDataContract.Operation#REVISION revision} are checked to see if they can be
 * applied to the device. If the data is valid the {@link RulesCheckReceiver} will obtain the
 * payload from the data app content provider via
 * {@link android.content.ContentProvider#openFile(Uri, String)} and pass the data to the system
 * server for installation via the
 * {@link RulesManager#requestInstall(ParcelFileDescriptor, byte[], Callback)}.
 */
// TODO(nfuller): Prevent multiple broadcasts being handled at once?
// TODO(nfuller): Improve logging
// TODO(nfuller): Make the rules check async?
// TODO(nfuller): Need async generally for SystemService calls from BroadcastReceiver?
public class RulesCheckReceiver extends BroadcastReceiver {
    final static String TAG = "RulesCheckReceiver";

    private RulesManager mRulesManager;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!RulesUpdaterContract.ACTION_TRIGGER_RULES_UPDATE_CHECK.equals(intent.getAction())) {
            // Unknown. Do nothing.
            Log.w(TAG, "Unrecognized intent action received: " + intent
                    + ", action=" + intent.getAction());
            return;
        }

        mRulesManager = (RulesManager) context.getSystemService("timezone");

        byte[] token = intent.getByteArrayExtra(RulesUpdaterContract.EXTRA_CHECK_TOKEN);

        // Note: We rely on the system server to check that the configured data application is the
        // one that exposes the content provider with the well-known authority, and is a privileged
        // application as required. It is *not* checked here and it is assumed the updater can trust
        // the data application.

        // Obtain the information about what the data app is telling us to do.
        DistroOperation operation = getOperation(context);
        if (operation == null) {
            Log.w(TAG, "Unable to read time zone operation. Halting check.");
            boolean success = true; // No point in retrying.
            handleCheckComplete(token, success);
            return;
        }

        // Try to do what the data app asked.
        Log.d(TAG, "Time zone operation: " + operation + " received.");
        switch (operation.mType) {
            case TimeZoneRulesDataContract.Operation.TYPE_NO_OP:
                // No-op. Just acknowledge the check.
                handleCheckComplete(token, true /* success */);
                break;
            case TimeZoneRulesDataContract.Operation.TYPE_UNINSTALL:
                handleUninstall(token);
                break;
            case TimeZoneRulesDataContract.Operation.TYPE_INSTALL:
                handleCopyAndInstall(context, token, operation.mDistroFormatVersion,
                        operation.mDistroRulesVersion);
                break;
            default:
                Log.w(TAG, "Unknown time zone operation: " + operation
                        + " received. Halting check.");
                final boolean success = true; // No point in retrying.
                handleCheckComplete(token, success);
        }
    }

    private DistroOperation getOperation(Context context) {
        Cursor c = context.getContentResolver()
                .query(TimeZoneRulesDataContract.Operation.CONTENT_URI,
                        new String[] {
                                TimeZoneRulesDataContract.Operation.TYPE,
                                TimeZoneRulesDataContract.Operation.DISTRO_MAJOR_VERSION,
                                TimeZoneRulesDataContract.Operation.DISTRO_MINOR_VERSION,
                                TimeZoneRulesDataContract.Operation.RULES_VERSION,
                                TimeZoneRulesDataContract.Operation.REVISION
                        },
                        null /* selection */, null /* selectionArgs */, null /* sortOrder */);
        try (Cursor cursor = c) {
            if (cursor == null) {
                Log.e(TAG, "Query returned null");
                return null;
            }
            if (!cursor.moveToFirst()) {
                Log.e(TAG, "Query returned empty results");
                return null;
            }

            try {
                String type = cursor.getString(0);
                DistroFormatVersion distroFormatVersion = null;
                DistroRulesVersion distroRulesVersion = null;
                if (TimeZoneRulesDataContract.Operation.TYPE_INSTALL.equals(type)) {
                    distroFormatVersion = new DistroFormatVersion(cursor.getInt(1),
                            cursor.getInt(2));
                    distroRulesVersion = new DistroRulesVersion(cursor.getString(3),
                            cursor.getInt(4));
                }
                return new DistroOperation(type, distroFormatVersion, distroRulesVersion);
            } catch (Exception e) {
                Log.e(TAG, "Error looking up distro operation / version", e);
                return null;
            }
        }
    }

    private void handleCopyAndInstall(Context context, byte[] checkToken,
            DistroFormatVersion distroFormatVersion, DistroRulesVersion distroRulesVersion) {
        // Decide whether to proceed with the install.
        RulesState rulesState = mRulesManager.getRulesState();
        if (!rulesState.isDistroFormatVersionSupported(distroFormatVersion)
            || rulesState.isSystemVersionNewerThan(distroRulesVersion)) {
            // Nothing to do.
            handleCheckComplete(checkToken, true /* success */);
            return;
        }

        ParcelFileDescriptor inputFileDescriptor = getDistroParcelFileDescriptor(context);
        if (inputFileDescriptor == null) {
            Log.e(TAG, "No local file created for distro. Halting.");
            return;
        }

        // Copying the ParcelFileDescriptor to a local file proves we can read it before passing it
        // on to the next stage. It also ensures that we have a hermetic copy of the data we know
        // the originating content provider cannot modify unexpectedly. If the next stage wants to
        // "seek" the ParcelFileDescriptor it can do so with fewer processes affected.
        File file = copyDataToLocalFile(context, inputFileDescriptor);
        if (file == null) {
            // It's possible this may get better if the problem is related to storage space so we
            // signal success := false so it may be retried.
            boolean success = false;
            handleCheckComplete(checkToken, success);
            return;
        }
        handleInstall(checkToken, file);
    }

    private static ParcelFileDescriptor getDistroParcelFileDescriptor(Context context) {
        ParcelFileDescriptor inputFileDescriptor;
        try {
            inputFileDescriptor = context.getContentResolver().openFileDescriptor(
                    TimeZoneRulesDataContract.Data.CONTENT_URI, "r");
            if (inputFileDescriptor == null) {
                throw new FileNotFoundException("ContentProvider returned null");
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Unable to open file descriptor"
                    + TimeZoneRulesDataContract.Data.CONTENT_URI, e);
            return null;
        }
        return inputFileDescriptor;
    }

    private static File copyDataToLocalFile(
            Context context, ParcelFileDescriptor inputFileDescriptor) {

        // Adopt the ParcelFileDescriptor into a try-with-resources so we will close it when we're
        // done regardless of the outcome.
        try (ParcelFileDescriptor pfd = inputFileDescriptor) {
            File localFile;
            try {
                localFile = File.createTempFile("temp", ".zip", context.getFilesDir());
            } catch (IOException e) {
                Log.e(TAG, "Unable to create local storage file", e);
                return null;
            }

            InputStream fis = new FileInputStream(pfd.getFileDescriptor(), false /* isFdOwner */);
            try (FileOutputStream fos = new FileOutputStream(localFile, false /* append */)) {
                Streams.copy(fis, fos);
            } catch (IOException e) {
                Log.e(TAG, "Unable to create asset storage file: " + localFile, e);
                return null;
            }
            return localFile;
        } catch (IOException e) {
            Log.e(TAG, "Unable to close ParcelFileDescriptor", e);
            return null;
        }
    }

    private void handleInstall(final byte[] checkToken, final File localFile) {
        // Create a ParcelFileDescriptor pointing to localFile.
        final ParcelFileDescriptor distroFileDescriptor;
        try {
            distroFileDescriptor =
                    ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "Unable to create ParcelFileDescriptor from " + localFile);
            handleCheckComplete(checkToken, false /* success */);
            return;
        } finally {
            // It is safe to delete the File at this point. The ParcelFileDescriptor has an open
            // file descriptor to it if we are successful, or it is not going to be used if we are
            // returning early.
            localFile.delete();
        }

        Callback callback = new Callback() {
            @Override
            public void onFinished(int status) {
                Log.i(TAG, "Finished install: " + status);
            }
        };

        // Adopt the distroFileDescriptor here so the local file descriptor is closed, whatever the
        // outcome.
        try (ParcelFileDescriptor pfd = distroFileDescriptor) {
            int requestStatus = mRulesManager.requestInstall(pfd, checkToken, callback);
            Log.i(TAG, "requestInstall() called, token=" + Arrays.toString(checkToken)
                    + ", returned " + requestStatus);
        } catch (Exception e) {
            Log.e(TAG, "Error calling requestInstall()", e);
        }
    }

    private void handleUninstall(byte[] checkToken) {
        Callback callback = new Callback() {
            @Override
            public void onFinished(int status) {
                Log.i(TAG, "Finished uninstall: " + status);
            }
        };

        try {
            int requestStatus = mRulesManager.requestUninstall(checkToken, callback);
            Log.i(TAG, "requestUninstall() called, token=" + Arrays.toString(checkToken)
                    + ", returned " + requestStatus);
        } catch (Exception e) {
            Log.e(TAG, "Error calling requestUninstall()", e);
        }
    }

    private void handleCheckComplete(final byte[] token, final boolean success) {
        try {
            mRulesManager.requestNothing(token, success);
            Log.i(TAG, "requestNothing() called, token=" + Arrays.toString(token)
                    + ", success=" + success);
        } catch (Exception e) {
            Log.e(TAG, "Error calling requestNothing()", e);
        }
    }

    private static class DistroOperation {
        final String mType;
        final DistroFormatVersion mDistroFormatVersion;
        final DistroRulesVersion mDistroRulesVersion;

        DistroOperation(String type, DistroFormatVersion distroFormatVersion,
                DistroRulesVersion distroRulesVersion) {
            mType = type;
            mDistroFormatVersion = distroFormatVersion;
            mDistroRulesVersion = distroRulesVersion;
        }

        @Override
        public String toString() {
            return "DistroOperation{" +
                    "mType='" + mType + '\'' +
                    ", mDistroFormatVersion=" + mDistroFormatVersion +
                    ", mDistroRulesVersion=" + mDistroRulesVersion +
                    '}';
        }
    }
}
