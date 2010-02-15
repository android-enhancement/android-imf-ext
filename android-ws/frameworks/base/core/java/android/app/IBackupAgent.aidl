/*
 * Copyright 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package android.app;

import android.os.ParcelFileDescriptor;
 
/**
 * Interface presented by applications being asked to participate in the
 * backup & restore mechanism.  End user code does not typically implement
 * this interface; they subclass BackupAgent instead.
 *
 * {@hide}
 */ 
interface IBackupAgent {
    /**
     * Request that the app perform an incremental backup.
     *
     * @param oldState Read-only file containing the description blob of the
     *        app's data state as of the last backup operation's completion.
     *        This file is empty or invalid when a full backup is being
     *        requested.
     *
     * @param data Read-write file, empty when onBackup() is called, that
     *        is the data destination for this backup pass's incrementals.
     *
     * @param newState Read-write file, empty when onBackup() is called,
     *        where the new state blob is to be recorded.
     */
    void doBackup(in ParcelFileDescriptor oldState,
            in ParcelFileDescriptor data,
            in ParcelFileDescriptor newState);

    /**
     * Restore an entire data snapshot to the application.
     *
     * @param data Read-only file containing the full data snapshot of the
     *        app's backup.  This is to be a <i>replacement</i> of the app's
     *        current data, not to be merged into it.
     *
     * @param appVersionCode The android:versionCode attribute of the application
     *        that created this data set.  This can help the agent distinguish among
     *        various historical backup content possibilities.
     *
     * @param newState Read-write file, empty when onRestore() is called,
     *        that is to be written with the state description that holds after
     *        the restore has been completed.
     */
    void doRestore(in ParcelFileDescriptor data, int appVersionCode,
            in ParcelFileDescriptor newState);
}
