/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import com.android.internal.os.AtomicFile;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.accounts.Account;
import android.backup.IBackupManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteQueryBuilder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.TimeZone;

/**
 * Singleton that tracks the sync data and overall sync
 * history on the device.
 *
 * @hide
 */
public class SyncStorageEngine extends Handler {
    private static final String TAG = "SyncManager";
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_FILE = false;

    // @VisibleForTesting
    static final long MILLIS_IN_4WEEKS = 1000L * 60 * 60 * 24 * 7 * 4;

    /** Enum value for a sync start event. */
    public static final int EVENT_START = 0;

    /** Enum value for a sync stop event. */
    public static final int EVENT_STOP = 1;

    // TODO: i18n -- grab these out of resources.
    /** String names for the sync event types. */
    public static final String[] EVENTS = { "START", "STOP" };

    /** Enum value for a server-initiated sync. */
    public static final int SOURCE_SERVER = 0;

    /** Enum value for a local-initiated sync. */
    public static final int SOURCE_LOCAL = 1;
    /**
     * Enum value for a poll-based sync (e.g., upon connection to
     * network)
     */
    public static final int SOURCE_POLL = 2;

    /** Enum value for a user-initiated sync. */
    public static final int SOURCE_USER = 3;

    private static final Intent SYNC_CONNECTION_SETTING_CHANGED_INTENT =
            new Intent("com.android.sync.SYNC_CONN_STATUS_CHANGED");

    // TODO: i18n -- grab these out of resources.
    /** String names for the sync source types. */
    public static final String[] SOURCES = { "SERVER",
                                             "LOCAL",
                                             "POLL",
                                             "USER" };

    // The MESG column will contain one of these or one of the Error types.
    public static final String MESG_SUCCESS = "success";
    public static final String MESG_CANCELED = "canceled";

    public static final int MAX_HISTORY = 100;

    private static final int MSG_WRITE_STATUS = 1;
    private static final long WRITE_STATUS_DELAY = 1000*60*10; // 10 minutes

    private static final int MSG_WRITE_STATISTICS = 2;
    private static final long WRITE_STATISTICS_DELAY = 1000*60*30; // 1/2 hour

    private static final boolean SYNC_ENABLED_DEFAULT = false;

    public static class PendingOperation {
        final Account account;
        final int syncSource;
        final String authority;
        final Bundle extras;        // note: read-only.

        int authorityId;
        byte[] flatExtras;

        PendingOperation(Account account, int source,
                String authority, Bundle extras) {
            this.account = account;
            this.syncSource = source;
            this.authority = authority;
            this.extras = extras != null ? new Bundle(extras) : extras;
            this.authorityId = -1;
        }

        PendingOperation(PendingOperation other) {
            this.account = other.account;
            this.syncSource = other.syncSource;
            this.authority = other.authority;
            this.extras = other.extras;
            this.authorityId = other.authorityId;
        }
    }

    static class AccountInfo {
        final Account account;
        final HashMap<String, AuthorityInfo> authorities =
                new HashMap<String, AuthorityInfo>();

        AccountInfo(Account account) {
            this.account = account;
        }
    }

    public static class AuthorityInfo {
        final Account account;
        final String authority;
        final int ident;
        boolean enabled;
        int syncable;

        AuthorityInfo(Account account, String authority, int ident) {
            this.account = account;
            this.authority = authority;
            this.ident = ident;
            enabled = SYNC_ENABLED_DEFAULT;
            syncable = -1; // default to "unknown"
        }
    }

    public static class SyncHistoryItem {
        int authorityId;
        int historyId;
        long eventTime;
        long elapsedTime;
        int source;
        int event;
        long upstreamActivity;
        long downstreamActivity;
        String mesg;
    }

    public static class DayStats {
        public final int day;
        public int successCount;
        public long successTime;
        public int failureCount;
        public long failureTime;

        public DayStats(int day) {
            this.day = day;
        }
    }

    // Primary list of all syncable authorities.  Also our global lock.
    private final SparseArray<AuthorityInfo> mAuthorities =
            new SparseArray<AuthorityInfo>();

    private final HashMap<Account, AccountInfo> mAccounts =
        new HashMap<Account, AccountInfo>();

    private final ArrayList<PendingOperation> mPendingOperations =
            new ArrayList<PendingOperation>();

    private ActiveSyncInfo mActiveSync;

    private final SparseArray<SyncStatusInfo> mSyncStatus =
            new SparseArray<SyncStatusInfo>();

    private final ArrayList<SyncHistoryItem> mSyncHistory =
            new ArrayList<SyncHistoryItem>();

    private final RemoteCallbackList<ISyncStatusObserver> mChangeListeners
            = new RemoteCallbackList<ISyncStatusObserver>();

    // We keep 4 weeks of stats.
    private final DayStats[] mDayStats = new DayStats[7*4];
    private final Calendar mCal;
    private int mYear;
    private int mYearInDays;

    private final Context mContext;
    private static volatile SyncStorageEngine sSyncStorageEngine = null;

    /**
     * This file contains the core engine state: all accounts and the
     * settings for them.  It must never be lost, and should be changed
     * infrequently, so it is stored as an XML file.
     */
    private final AtomicFile mAccountInfoFile;

    /**
     * This file contains the current sync status.  We would like to retain
     * it across boots, but its loss is not the end of the world, so we store
     * this information as binary data.
     */
    private final AtomicFile mStatusFile;

    /**
     * This file contains sync statistics.  This is purely debugging information
     * so is written infrequently and can be thrown away at any time.
     */
    private final AtomicFile mStatisticsFile;

    /**
     * This file contains the pending sync operations.  It is a binary file,
     * which must be updated every time an operation is added or removed,
     * so we have special handling of it.
     */
    private final AtomicFile mPendingFile;
    private static final int PENDING_FINISH_TO_WRITE = 4;
    private int mNumPendingFinished = 0;

    private int mNextHistoryId = 0;
    private boolean mMasterSyncAutomatically = true;

    private SyncStorageEngine(Context context) {
        mContext = context;
        sSyncStorageEngine = this;

        mCal = Calendar.getInstance(TimeZone.getTimeZone("GMT+0"));

        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        File syncDir = new File(systemDir, "sync");
        mAccountInfoFile = new AtomicFile(new File(syncDir, "accounts.xml"));
        mStatusFile = new AtomicFile(new File(syncDir, "status.bin"));
        mPendingFile = new AtomicFile(new File(syncDir, "pending.bin"));
        mStatisticsFile = new AtomicFile(new File(syncDir, "stats.bin"));

        readAccountInfoLocked();
        readStatusLocked();
        readPendingOperationsLocked();
        readStatisticsLocked();
        readLegacyAccountInfoLocked();
    }

    public static SyncStorageEngine newTestInstance(Context context) {
        return new SyncStorageEngine(context);
    }

    public static void init(Context context) {
        if (sSyncStorageEngine != null) {
            throw new IllegalStateException("already initialized");
        }
        sSyncStorageEngine = new SyncStorageEngine(context);
    }

    public static SyncStorageEngine getSingleton() {
        if (sSyncStorageEngine == null) {
            throw new IllegalStateException("not initialized");
        }
        return sSyncStorageEngine;
    }

    @Override public void handleMessage(Message msg) {
        if (msg.what == MSG_WRITE_STATUS) {
            synchronized (mAccounts) {
                writeStatusLocked();
            }
        } else if (msg.what == MSG_WRITE_STATISTICS) {
            synchronized (mAccounts) {
                writeStatisticsLocked();
            }
        }
    }

    public void addStatusChangeListener(int mask, ISyncStatusObserver callback) {
        synchronized (mAuthorities) {
            mChangeListeners.register(callback, mask);
        }
    }

    public void removeStatusChangeListener(ISyncStatusObserver callback) {
        synchronized (mAuthorities) {
            mChangeListeners.unregister(callback);
        }
    }

    private void reportChange(int which) {
        ArrayList<ISyncStatusObserver> reports = null;
        synchronized (mAuthorities) {
            int i = mChangeListeners.beginBroadcast();
            while (i > 0) {
                i--;
                Integer mask = (Integer)mChangeListeners.getBroadcastCookie(i);
                if ((which & mask.intValue()) == 0) {
                    continue;
                }
                if (reports == null) {
                    reports = new ArrayList<ISyncStatusObserver>(i);
                }
                reports.add(mChangeListeners.getBroadcastItem(i));
            }
            mChangeListeners.finishBroadcast();
        }

        if (DEBUG) Log.v(TAG, "reportChange " + which + " to: " + reports);

        if (reports != null) {
            int i = reports.size();
            while (i > 0) {
                i--;
                try {
                    reports.get(i).onStatusChanged(which);
                } catch (RemoteException e) {
                    // The remote callback list will take care of this for us.
                }
            }
        }
    }

    public boolean getSyncAutomatically(Account account, String providerName) {
        synchronized (mAuthorities) {
            if (account != null) {
                AuthorityInfo authority = getAuthorityLocked(account, providerName,
                        "getSyncAutomatically");
                return authority != null && authority.enabled;
            }

            int i = mAuthorities.size();
            while (i > 0) {
                i--;
                AuthorityInfo authority = mAuthorities.valueAt(i);
                if (authority.authority.equals(providerName)
                        && authority.enabled) {
                    return true;
                }
            }
            return false;
        }
    }

    public void setSyncAutomatically(Account account, String providerName, boolean sync) {
        boolean wasEnabled;
        synchronized (mAuthorities) {
            AuthorityInfo authority = getOrCreateAuthorityLocked(account, providerName, -1, false);
            wasEnabled = authority.enabled;
            authority.enabled = sync;
            writeAccountInfoLocked();
        }

        if (!wasEnabled && sync) {
            mContext.getContentResolver().requestSync(account, providerName, new Bundle());
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
    }

    public int getIsSyncable(Account account, String providerName) {
        synchronized (mAuthorities) {
            if (account != null) {
                AuthorityInfo authority = getAuthorityLocked(account, providerName,
                        "getIsSyncable");
                if (authority == null) {
                    return -1;
                }
                return authority.syncable;
            }

            int i = mAuthorities.size();
            while (i > 0) {
                i--;
                AuthorityInfo authority = mAuthorities.valueAt(i);
                if (authority.authority.equals(providerName)) {
                    return authority.syncable;
                }
            }
            return -1;
        }
    }

    public void setIsSyncable(Account account, String providerName, int syncable) {
        int oldState;
        if (syncable > 1) {
            syncable = 1;
        } else if (syncable < -1) {
            syncable = -1;
        }
        Log.d(TAG, "setIsSyncable: " + account + ", provider " + providerName + " -> " + syncable);
        synchronized (mAuthorities) {
            AuthorityInfo authority = getOrCreateAuthorityLocked(account, providerName, -1, false);
            oldState = authority.syncable;
            authority.syncable = syncable;
            writeAccountInfoLocked();
        }

        if (oldState <= 0 && syncable > 0) {
            mContext.getContentResolver().requestSync(account, providerName, new Bundle());
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
    }

    public void setMasterSyncAutomatically(boolean flag) {
        boolean old;
        synchronized (mAuthorities) {
            old = mMasterSyncAutomatically;
            mMasterSyncAutomatically = flag;
            writeAccountInfoLocked();
        }
        if (!old && flag) {
            mContext.getContentResolver().requestSync(null, null, new Bundle());
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS);
        mContext.sendBroadcast(SYNC_CONNECTION_SETTING_CHANGED_INTENT);
    }

    public boolean getMasterSyncAutomatically() {
        synchronized (mAuthorities) {
            return mMasterSyncAutomatically;
        }
    }

    public AuthorityInfo getAuthority(Account account, String authority) {
        synchronized (mAuthorities) {
            return getAuthorityLocked(account, authority, null);
        }
    }

    public AuthorityInfo getAuthority(int authorityId) {
        synchronized (mAuthorities) {
            return mAuthorities.get(authorityId);
        }
    }

    /**
     * Returns true if there is currently a sync operation for the given
     * account or authority in the pending list, or actively being processed.
     */
    public boolean isSyncActive(Account account, String authority) {
        synchronized (mAuthorities) {
            int i = mPendingOperations.size();
            while (i > 0) {
                i--;
                // TODO(fredq): this probably shouldn't be considering
                // pending operations.
                PendingOperation op = mPendingOperations.get(i);
                if (op.account.equals(account) && op.authority.equals(authority)) {
                    return true;
                }
            }

            if (mActiveSync != null) {
                AuthorityInfo ainfo = getAuthority(mActiveSync.authorityId);
                if (ainfo != null && ainfo.account.equals(account)
                        && ainfo.authority.equals(authority)) {
                    return true;
                }
            }
        }

        return false;
    }

    public PendingOperation insertIntoPending(PendingOperation op) {
        synchronized (mAuthorities) {
            if (DEBUG) Log.v(TAG, "insertIntoPending: account=" + op.account
                    + " auth=" + op.authority
                    + " src=" + op.syncSource
                    + " extras=" + op.extras);

            AuthorityInfo authority = getOrCreateAuthorityLocked(op.account,
                    op.authority,
                    -1 /* desired identifier */,
                    true /* write accounts to storage */);
            if (authority == null) {
                return null;
            }

            op = new PendingOperation(op);
            op.authorityId = authority.ident;
            mPendingOperations.add(op);
            appendPendingOperationLocked(op);

            SyncStatusInfo status = getOrCreateSyncStatusLocked(authority.ident);
            status.pending = true;
            status.initialize = op.extras != null && 
                 op.extras.containsKey(ContentResolver.SYNC_EXTRAS_INITIALIZE) &&
                 op.extras.getBoolean(ContentResolver.SYNC_EXTRAS_INITIALIZE);
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_PENDING);
        return op;
    }

    public boolean deleteFromPending(PendingOperation op) {
        boolean res = false;
        synchronized (mAuthorities) {
            if (DEBUG) Log.v(TAG, "deleteFromPending: account=" + op.account
                    + " auth=" + op.authority
                    + " src=" + op.syncSource
                    + " extras=" + op.extras);
            if (mPendingOperations.remove(op)) {
                if (mPendingOperations.size() == 0
                        || mNumPendingFinished >= PENDING_FINISH_TO_WRITE) {
                    writePendingOperationsLocked();
                    mNumPendingFinished = 0;
                } else {
                    mNumPendingFinished++;
                }

                AuthorityInfo authority = getAuthorityLocked(op.account, op.authority,
                        "deleteFromPending");
                if (authority != null) {
                    if (DEBUG) Log.v(TAG, "removing - " + authority);
                    final int N = mPendingOperations.size();
                    boolean morePending = false;
                    for (int i=0; i<N; i++) {
                        PendingOperation cur = mPendingOperations.get(i);
                        if (cur.account.equals(op.account)
                                && cur.authority.equals(op.authority)) {
                            morePending = true;
                            break;
                        }
                    }

                    if (!morePending) {
                        if (DEBUG) Log.v(TAG, "no more pending!");
                        SyncStatusInfo status = getOrCreateSyncStatusLocked(authority.ident);
                        status.pending = false;
                    }
                }

                res = true;
            }
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_PENDING);
        return res;
    }

    public int clearPending() {
        int num;
        synchronized (mAuthorities) {
            if (DEBUG) Log.v(TAG, "clearPending");
            num = mPendingOperations.size();
            mPendingOperations.clear();
            final int N = mSyncStatus.size();
            for (int i=0; i<N; i++) {
                mSyncStatus.valueAt(i).pending = false;
            }
            writePendingOperationsLocked();
        }
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_PENDING);
        return num;
    }

    /**
     * Return a copy of the current array of pending operations.  The
     * PendingOperation objects are the real objects stored inside, so that
     * they can be used with deleteFromPending().
     */
    public ArrayList<PendingOperation> getPendingOperations() {
        synchronized (mAuthorities) {
            return new ArrayList<PendingOperation>(mPendingOperations);
        }
    }

    /**
     * Return the number of currently pending operations.
     */
    public int getPendingOperationCount() {
        synchronized (mAuthorities) {
            return mPendingOperations.size();
        }
    }

    /**
     * Called when the set of account has changed, given the new array of
     * active accounts.
     */
    public void doDatabaseCleanup(Account[] accounts) {
        synchronized (mAuthorities) {
            if (DEBUG) Log.w(TAG, "Updating for new accounts...");
            SparseArray<AuthorityInfo> removing = new SparseArray<AuthorityInfo>();
            Iterator<AccountInfo> accIt = mAccounts.values().iterator();
            while (accIt.hasNext()) {
                AccountInfo acc = accIt.next();
                if (!ArrayUtils.contains(accounts, acc.account)) {
                    // This account no longer exists...
                    if (DEBUG) Log.w(TAG, "Account removed: " + acc.account);
                    for (AuthorityInfo auth : acc.authorities.values()) {
                        removing.put(auth.ident, auth);
                    }
                    accIt.remove();
                }
            }

            // Clean out all data structures.
            int i = removing.size();
            if (i > 0) {
                while (i > 0) {
                    i--;
                    int ident = removing.keyAt(i);
                    mAuthorities.remove(ident);
                    int j = mSyncStatus.size();
                    while (j > 0) {
                        j--;
                        if (mSyncStatus.keyAt(j) == ident) {
                            mSyncStatus.remove(mSyncStatus.keyAt(j));
                        }
                    }
                    j = mSyncHistory.size();
                    while (j > 0) {
                        j--;
                        if (mSyncHistory.get(j).authorityId == ident) {
                            mSyncHistory.remove(j);
                        }
                    }
                }
                writeAccountInfoLocked();
                writeStatusLocked();
                writePendingOperationsLocked();
                writeStatisticsLocked();
            }
        }
    }

    /**
     * Called when the currently active sync is changing (there can only be
     * one at a time).  Either supply a valid ActiveSyncContext with information
     * about the sync, or null to stop the currently active sync.
     */
    public void setActiveSync(SyncManager.ActiveSyncContext activeSyncContext) {
        synchronized (mAuthorities) {
            if (activeSyncContext != null) {
                if (DEBUG) Log.v(TAG, "setActiveSync: account="
                        + activeSyncContext.mSyncOperation.account
                        + " auth=" + activeSyncContext.mSyncOperation.authority
                        + " src=" + activeSyncContext.mSyncOperation.syncSource
                        + " extras=" + activeSyncContext.mSyncOperation.extras);
                if (mActiveSync != null) {
                    Log.w(TAG, "setActiveSync called with existing active sync!");
                }
                AuthorityInfo authority = getAuthorityLocked(
                        activeSyncContext.mSyncOperation.account,
                        activeSyncContext.mSyncOperation.authority,
                        "setActiveSync");
                if (authority == null) {
                    return;
                }
                mActiveSync = new ActiveSyncInfo(authority.ident,
                        authority.account, authority.authority,
                        activeSyncContext.mStartTime);
            } else {
                if (DEBUG) Log.v(TAG, "setActiveSync: null");
                mActiveSync = null;
            }
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE);
    }

    /**
     * To allow others to send active change reports, to poke clients.
     */
    public void reportActiveChange() {
        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE);
    }

    /**
     * Note that sync has started for the given account and authority.
     */
    public long insertStartSyncEvent(Account accountName, String authorityName,
            long now, int source) {
        long id;
        synchronized (mAuthorities) {
            if (DEBUG) Log.v(TAG, "insertStartSyncEvent: account=" + accountName
                    + " auth=" + authorityName + " source=" + source);
            AuthorityInfo authority = getAuthorityLocked(accountName, authorityName,
                    "insertStartSyncEvent");
            if (authority == null) {
                return -1;
            }
            SyncHistoryItem item = new SyncHistoryItem();
            item.authorityId = authority.ident;
            item.historyId = mNextHistoryId++;
            if (mNextHistoryId < 0) mNextHistoryId = 0;
            item.eventTime = now;
            item.source = source;
            item.event = EVENT_START;
            mSyncHistory.add(0, item);
            while (mSyncHistory.size() > MAX_HISTORY) {
                mSyncHistory.remove(mSyncHistory.size()-1);
            }
            id = item.historyId;
            if (DEBUG) Log.v(TAG, "returning historyId " + id);
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_STATUS);
        return id;
    }

    public void stopSyncEvent(long historyId, long elapsedTime, String resultMessage,
            long downstreamActivity, long upstreamActivity) {
        synchronized (mAuthorities) {
            if (DEBUG) Log.v(TAG, "stopSyncEvent: historyId=" + historyId);
            SyncHistoryItem item = null;
            int i = mSyncHistory.size();
            while (i > 0) {
                i--;
                item = mSyncHistory.get(i);
                if (item.historyId == historyId) {
                    break;
                }
                item = null;
            }

            if (item == null) {
                Log.w(TAG, "stopSyncEvent: no history for id " + historyId);
                return;
            }

            item.elapsedTime = elapsedTime;
            item.event = EVENT_STOP;
            item.mesg = resultMessage;
            item.downstreamActivity = downstreamActivity;
            item.upstreamActivity = upstreamActivity;

            SyncStatusInfo status = getOrCreateSyncStatusLocked(item.authorityId);

            status.numSyncs++;
            status.totalElapsedTime += elapsedTime;
            switch (item.source) {
                case SOURCE_LOCAL:
                    status.numSourceLocal++;
                    break;
                case SOURCE_POLL:
                    status.numSourcePoll++;
                    break;
                case SOURCE_USER:
                    status.numSourceUser++;
                    break;
                case SOURCE_SERVER:
                    status.numSourceServer++;
                    break;
            }

            boolean writeStatisticsNow = false;
            int day = getCurrentDayLocked();
            if (mDayStats[0] == null) {
                mDayStats[0] = new DayStats(day);
            } else if (day != mDayStats[0].day) {
                System.arraycopy(mDayStats, 0, mDayStats, 1, mDayStats.length-1);
                mDayStats[0] = new DayStats(day);
                writeStatisticsNow = true;
            } else if (mDayStats[0] == null) {
            }
            final DayStats ds = mDayStats[0];

            final long lastSyncTime = (item.eventTime + elapsedTime);
            boolean writeStatusNow = false;
            if (MESG_SUCCESS.equals(resultMessage)) {
                // - if successful, update the successful columns
                if (status.lastSuccessTime == 0 || status.lastFailureTime != 0) {
                    writeStatusNow = true;
                }
                status.lastSuccessTime = lastSyncTime;
                status.lastSuccessSource = item.source;
                status.lastFailureTime = 0;
                status.lastFailureSource = -1;
                status.lastFailureMesg = null;
                status.initialFailureTime = 0;
                ds.successCount++;
                ds.successTime += elapsedTime;
            } else if (!MESG_CANCELED.equals(resultMessage)) {
                if (status.lastFailureTime == 0) {
                    writeStatusNow = true;
                }
                status.lastFailureTime = lastSyncTime;
                status.lastFailureSource = item.source;
                status.lastFailureMesg = resultMessage;
                if (status.initialFailureTime == 0) {
                    status.initialFailureTime = lastSyncTime;
                }
                ds.failureCount++;
                ds.failureTime += elapsedTime;
            }

            if (writeStatusNow) {
                writeStatusLocked();
            } else if (!hasMessages(MSG_WRITE_STATUS)) {
                sendMessageDelayed(obtainMessage(MSG_WRITE_STATUS),
                        WRITE_STATUS_DELAY);
            }
            if (writeStatisticsNow) {
                writeStatisticsLocked();
            } else if (!hasMessages(MSG_WRITE_STATISTICS)) {
                sendMessageDelayed(obtainMessage(MSG_WRITE_STATISTICS),
                        WRITE_STATISTICS_DELAY);
            }
        }

        reportChange(ContentResolver.SYNC_OBSERVER_TYPE_STATUS);
    }

    /**
     * Return the currently active sync information, or null if there is no
     * active sync.  Note that the returned object is the real, live active
     * sync object, so be careful what you do with it.
     */
    public ActiveSyncInfo getActiveSync() {
        synchronized (mAuthorities) {
            return mActiveSync;
        }
    }

    /**
     * Return an array of the current sync status for all authorities.  Note
     * that the objects inside the array are the real, live status objects,
     * so be careful what you do with them.
     */
    public ArrayList<SyncStatusInfo> getSyncStatus() {
        synchronized (mAuthorities) {
            final int N = mSyncStatus.size();
            ArrayList<SyncStatusInfo> ops = new ArrayList<SyncStatusInfo>(N);
            for (int i=0; i<N; i++) {
                ops.add(mSyncStatus.valueAt(i));
            }
            return ops;
        }
    }

    /**
     * Returns the status that matches the authority and account.
     *
     * @param account the account we want to check
     * @param authority the authority whose row should be selected
     * @return the SyncStatusInfo for the authority, or null if none exists
     */
    public SyncStatusInfo getStatusByAccountAndAuthority(Account account, String authority) {
        if (account == null || authority == null) {
          throw new IllegalArgumentException();
        }
        synchronized (mAuthorities) {
            final int N = mSyncStatus.size();
            for (int i=0; i<N; i++) {
                SyncStatusInfo cur = mSyncStatus.valueAt(i);
                AuthorityInfo ainfo = mAuthorities.get(cur.authorityId);

                if (ainfo != null && ainfo.authority.equals(authority) &&
                    account.equals(ainfo.account)) {
                  return cur;
                }
            }
            return null;
        }
    }

    /**
     * Return true if the pending status is true of any matching authorities.
     */
    public boolean isSyncPending(Account account, String authority) {
        synchronized (mAuthorities) {
            final int N = mSyncStatus.size();
            for (int i=0; i<N; i++) {
                SyncStatusInfo cur = mSyncStatus.valueAt(i);
                AuthorityInfo ainfo = mAuthorities.get(cur.authorityId);
                if (ainfo == null) {
                    continue;
                }
                if (account != null && !ainfo.account.equals(account)) {
                    continue;
                }
                if (ainfo.authority.equals(authority) && cur.pending) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Return an array of the current sync status for all authorities.  Note
     * that the objects inside the array are the real, live status objects,
     * so be careful what you do with them.
     */
    public ArrayList<SyncHistoryItem> getSyncHistory() {
        synchronized (mAuthorities) {
            final int N = mSyncHistory.size();
            ArrayList<SyncHistoryItem> items = new ArrayList<SyncHistoryItem>(N);
            for (int i=0; i<N; i++) {
                items.add(mSyncHistory.get(i));
            }
            return items;
        }
    }

    /**
     * Return an array of the current per-day statistics.  Note
     * that the objects inside the array are the real, live status objects,
     * so be careful what you do with them.
     */
    public DayStats[] getDayStatistics() {
        synchronized (mAuthorities) {
            DayStats[] ds = new DayStats[mDayStats.length];
            System.arraycopy(mDayStats, 0, ds, 0, ds.length);
            return ds;
        }
    }

    /**
     * If sync is failing for any of the provider/accounts then determine the time at which it
     * started failing and return the earliest time over all the provider/accounts. If none are
     * failing then return 0.
     */
    public long getInitialSyncFailureTime() {
        synchronized (mAuthorities) {
            if (!mMasterSyncAutomatically) {
                return 0;
            }

            long oldest = 0;
            int i = mSyncStatus.size();
            while (i > 0) {
                i--;
                SyncStatusInfo stats = mSyncStatus.valueAt(i);
                AuthorityInfo authority = mAuthorities.get(stats.authorityId);
                if (authority != null && authority.enabled) {
                    if (oldest == 0 || stats.initialFailureTime < oldest) {
                        oldest = stats.initialFailureTime;
                    }
                }
            }

            return oldest;
        }
    }

    private int getCurrentDayLocked() {
        mCal.setTimeInMillis(System.currentTimeMillis());
        final int dayOfYear = mCal.get(Calendar.DAY_OF_YEAR);
        if (mYear != mCal.get(Calendar.YEAR)) {
            mYear = mCal.get(Calendar.YEAR);
            mCal.clear();
            mCal.set(Calendar.YEAR, mYear);
            mYearInDays = (int)(mCal.getTimeInMillis()/86400000);
        }
        return dayOfYear + mYearInDays;
    }

    /**
     * Retrieve an authority, returning null if one does not exist.
     *
     * @param accountName The name of the account for the authority.
     * @param authorityName The name of the authority itself.
     * @param tag If non-null, this will be used in a log message if the
     * requested authority does not exist.
     */
    private AuthorityInfo getAuthorityLocked(Account accountName, String authorityName,
            String tag) {
        AccountInfo account = mAccounts.get(accountName);
        if (account == null) {
            if (tag != null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, tag + ": unknown account " + accountName);
                }
            }
            return null;
        }
        AuthorityInfo authority = account.authorities.get(authorityName);
        if (authority == null) {
            if (tag != null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, tag + ": unknown authority " + authorityName);
                }
            }
            return null;
        }

        return authority;
    }

    private AuthorityInfo getOrCreateAuthorityLocked(Account accountName,
            String authorityName, int ident, boolean doWrite) {
        AccountInfo account = mAccounts.get(accountName);
        if (account == null) {
            account = new AccountInfo(accountName);
            mAccounts.put(accountName, account);
        }
        AuthorityInfo authority = account.authorities.get(authorityName);
        if (authority == null) {
            if (ident < 0) {
                // Look for a new identifier for this authority.
                final int N = mAuthorities.size();
                ident = 0;
                for (int i=0; i<N; i++) {
                    if (mAuthorities.valueAt(i).ident > ident) {
                        break;
                    }
                    ident++;
                }
            }
            if (DEBUG) Log.v(TAG, "created a new AuthorityInfo for " + accountName
                    + ", provider " + authorityName);
            authority = new AuthorityInfo(accountName, authorityName, ident);
            account.authorities.put(authorityName, authority);
            mAuthorities.put(ident, authority);
            if (doWrite) {
                writeAccountInfoLocked();
            }
        }

        return authority;
    }

    private SyncStatusInfo getOrCreateSyncStatusLocked(int authorityId) {
        SyncStatusInfo status = mSyncStatus.get(authorityId);
        if (status == null) {
            status = new SyncStatusInfo(authorityId);
            mSyncStatus.put(authorityId, status);
        }
        return status;
    }

    public void writeAllState() {
        synchronized (mAuthorities) {
            // Account info is always written so no need to do it here.

            if (mNumPendingFinished > 0) {
                // Only write these if they are out of date.
                writePendingOperationsLocked();
            }

            // Just always write these...  they are likely out of date.
            writeStatusLocked();
            writeStatisticsLocked();
        }
    }

    /**
     * Read all account information back in to the initial engine state.
     */
    private void readAccountInfoLocked() {
        FileInputStream fis = null;
        try {
            fis = mAccountInfoFile.openRead();
            if (DEBUG_FILE) Log.v(TAG, "Reading " + mAccountInfoFile.getBaseFile());
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG) {
                eventType = parser.next();
            }
            String tagName = parser.getName();
            if ("accounts".equals(tagName)) {
                String listen = parser.getAttributeValue(
                        null, "listen-for-tickles");
                mMasterSyncAutomatically = listen == null
                            || Boolean.parseBoolean(listen);
                eventType = parser.next();
                do {
                    if (eventType == XmlPullParser.START_TAG
                            && parser.getDepth() == 2) {
                        tagName = parser.getName();
                        if ("authority".equals(tagName)) {
                            int id = -1;
                            try {
                                id = Integer.parseInt(parser.getAttributeValue(
                                        null, "id"));
                            } catch (NumberFormatException e) {
                            } catch (NullPointerException e) {
                            }
                            if (id >= 0) {
                                String accountName = parser.getAttributeValue(
                                        null, "account");
                                String accountType = parser.getAttributeValue(
                                        null, "type");
                                if (accountType == null) {
                                    accountType = "com.google";
                                }
                                String authorityName = parser.getAttributeValue(
                                        null, "authority");
                                String enabled = parser.getAttributeValue(
                                        null, "enabled");
                                String syncable = parser.getAttributeValue(null, "syncable");
                                AuthorityInfo authority = mAuthorities.get(id);
                                if (DEBUG_FILE) Log.v(TAG, "Adding authority: account="
                                        + accountName + " auth=" + authorityName
                                        + " enabled=" + enabled
                                        + " syncable=" + syncable);
                                if (authority == null) {
                                    if (DEBUG_FILE) Log.v(TAG, "Creating entry");
                                    authority = getOrCreateAuthorityLocked(
                                            new Account(accountName, accountType),
                                            authorityName, id, false);
                                }
                                if (authority != null) {
                                    authority.enabled = enabled == null
                                            || Boolean.parseBoolean(enabled);
                                    if ("unknown".equals(syncable)) {
                                        authority.syncable = -1;
                                    } else {
                                        authority.syncable =
                                                (syncable == null || Boolean.parseBoolean(enabled))
                                                        ? 1
                                                        : 0;
                                    }
                                } else {
                                    Log.w(TAG, "Failure adding authority: account="
                                            + accountName + " auth=" + authorityName
                                            + " enabled=" + enabled
                                            + " syncable=" + syncable);
                                }
                            }
                        }
                    }
                    eventType = parser.next();
                } while (eventType != XmlPullParser.END_DOCUMENT);
            }
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Error reading accounts", e);
        } catch (java.io.IOException e) {
            if (fis == null) Log.i(TAG, "No initial accounts");
            else Log.w(TAG, "Error reading accounts", e);
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (java.io.IOException e1) {
                }
            }
        }
    }

    /**
     * Write all account information to the account file.
     */
    private void writeAccountInfoLocked() {
        if (DEBUG_FILE) Log.v(TAG, "Writing new " + mAccountInfoFile.getBaseFile());
        FileOutputStream fos = null;

        try {
            fos = mAccountInfoFile.startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, "utf-8");
            out.startDocument(null, true);
            out.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            out.startTag(null, "accounts");
            if (!mMasterSyncAutomatically) {
                out.attribute(null, "listen-for-tickles", "false");
            }

            final int N = mAuthorities.size();
            for (int i=0; i<N; i++) {
                AuthorityInfo authority = mAuthorities.valueAt(i);
                out.startTag(null, "authority");
                out.attribute(null, "id", Integer.toString(authority.ident));
                out.attribute(null, "account", authority.account.name);
                out.attribute(null, "type", authority.account.type);
                out.attribute(null, "authority", authority.authority);
                if (!authority.enabled) {
                    out.attribute(null, "enabled", "false");
                }
                if (authority.syncable < 0) {
                    out.attribute(null, "syncable", "unknown");
                } else if (authority.syncable == 0) {
                    out.attribute(null, "syncable", "false");
                }
                out.endTag(null, "authority");
            }

            out.endTag(null, "accounts");

            out.endDocument();

            mAccountInfoFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Log.w(TAG, "Error writing accounts", e1);
            if (fos != null) {
                mAccountInfoFile.failWrite(fos);
            }
        }
    }

    static int getIntColumn(Cursor c, String name) {
        return c.getInt(c.getColumnIndex(name));
    }

    static long getLongColumn(Cursor c, String name) {
        return c.getLong(c.getColumnIndex(name));
    }

    /**
     * Load sync engine state from the old syncmanager database, and then
     * erase it.  Note that we don't deal with pending operations, active
     * sync, or history.
     */
    private void readLegacyAccountInfoLocked() {
        // Look for old database to initialize from.
        File file = mContext.getDatabasePath("syncmanager.db");
        if (!file.exists()) {
            return;
        }
        String path = file.getPath();
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(path, null,
                    SQLiteDatabase.OPEN_READONLY);
        } catch (SQLiteException e) {
        }

        if (db != null) {
            final boolean hasType = db.getVersion() >= 11;

            // Copy in all of the status information, as well as accounts.
            if (DEBUG_FILE) Log.v(TAG, "Reading legacy sync accounts db");
            SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
            qb.setTables("stats, status");
            HashMap<String,String> map = new HashMap<String,String>();
            map.put("_id", "status._id as _id");
            map.put("account", "stats.account as account");
            if (hasType) {
                map.put("account_type", "stats.account_type as account_type");
            }
            map.put("authority", "stats.authority as authority");
            map.put("totalElapsedTime", "totalElapsedTime");
            map.put("numSyncs", "numSyncs");
            map.put("numSourceLocal", "numSourceLocal");
            map.put("numSourcePoll", "numSourcePoll");
            map.put("numSourceServer", "numSourceServer");
            map.put("numSourceUser", "numSourceUser");
            map.put("lastSuccessSource", "lastSuccessSource");
            map.put("lastSuccessTime", "lastSuccessTime");
            map.put("lastFailureSource", "lastFailureSource");
            map.put("lastFailureTime", "lastFailureTime");
            map.put("lastFailureMesg", "lastFailureMesg");
            map.put("pending", "pending");
            qb.setProjectionMap(map);
            qb.appendWhere("stats._id = status.stats_id");
            Cursor c = qb.query(db, null, null, null, null, null, null);
            while (c.moveToNext()) {
                String accountName = c.getString(c.getColumnIndex("account"));
                String accountType = hasType
                        ? c.getString(c.getColumnIndex("account_type")) : null;
                if (accountType == null) {
                    accountType = "com.google";
                }
                String authorityName = c.getString(c.getColumnIndex("authority"));
                AuthorityInfo authority = this.getOrCreateAuthorityLocked(
                        new Account(accountName, accountType),
                        authorityName, -1, false);
                if (authority != null) {
                    int i = mSyncStatus.size();
                    boolean found = false;
                    SyncStatusInfo st = null;
                    while (i > 0) {
                        i--;
                        st = mSyncStatus.valueAt(i);
                        if (st.authorityId == authority.ident) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        st = new SyncStatusInfo(authority.ident);
                        mSyncStatus.put(authority.ident, st);
                    }
                    st.totalElapsedTime = getLongColumn(c, "totalElapsedTime");
                    st.numSyncs = getIntColumn(c, "numSyncs");
                    st.numSourceLocal = getIntColumn(c, "numSourceLocal");
                    st.numSourcePoll = getIntColumn(c, "numSourcePoll");
                    st.numSourceServer = getIntColumn(c, "numSourceServer");
                    st.numSourceUser = getIntColumn(c, "numSourceUser");
                    st.lastSuccessSource = getIntColumn(c, "lastSuccessSource");
                    st.lastSuccessTime = getLongColumn(c, "lastSuccessTime");
                    st.lastFailureSource = getIntColumn(c, "lastFailureSource");
                    st.lastFailureTime = getLongColumn(c, "lastFailureTime");
                    st.lastFailureMesg = c.getString(c.getColumnIndex("lastFailureMesg"));
                    st.pending = getIntColumn(c, "pending") != 0;
                }
            }

            c.close();

            // Retrieve the settings.
            qb = new SQLiteQueryBuilder();
            qb.setTables("settings");
            c = qb.query(db, null, null, null, null, null, null);
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndex("name"));
                String value = c.getString(c.getColumnIndex("value"));
                if (name == null) continue;
                if (name.equals("listen_for_tickles")) {
                    setMasterSyncAutomatically(value == null || Boolean.parseBoolean(value));
                } else if (name.startsWith("sync_provider_")) {
                    String provider = name.substring("sync_provider_".length(),
                            name.length());
                    int i = mAuthorities.size();
                    while (i > 0) {
                        i--;
                        AuthorityInfo authority = mAuthorities.valueAt(i);
                        if (authority.authority.equals(provider)) {
                            authority.enabled = value == null || Boolean.parseBoolean(value);
                            authority.syncable = 1;
                        }
                    }
                }
            }

            c.close();

            db.close();

            writeAccountInfoLocked();
            writeStatusLocked();
            (new File(path)).delete();
        }
    }

    public static final int STATUS_FILE_END = 0;
    public static final int STATUS_FILE_ITEM = 100;

    /**
     * Read all sync status back in to the initial engine state.
     */
    private void readStatusLocked() {
        if (DEBUG_FILE) Log.v(TAG, "Reading " + mStatusFile.getBaseFile());
        try {
            byte[] data = mStatusFile.readFully();
            Parcel in = Parcel.obtain();
            in.unmarshall(data, 0, data.length);
            in.setDataPosition(0);
            int token;
            while ((token=in.readInt()) != STATUS_FILE_END) {
                if (token == STATUS_FILE_ITEM) {
                    SyncStatusInfo status = new SyncStatusInfo(in);
                    if (mAuthorities.indexOfKey(status.authorityId) >= 0) {
                        status.pending = false;
                        if (DEBUG_FILE) Log.v(TAG, "Adding status for id "
                                + status.authorityId);
                        mSyncStatus.put(status.authorityId, status);
                    }
                } else {
                    // Ooops.
                    Log.w(TAG, "Unknown status token: " + token);
                    break;
                }
            }
        } catch (java.io.IOException e) {
            Log.i(TAG, "No initial status");
        }
    }

    /**
     * Write all sync status to the sync status file.
     */
    private void writeStatusLocked() {
        if (DEBUG_FILE) Log.v(TAG, "Writing new " + mStatusFile.getBaseFile());

        // The file is being written, so we don't need to have a scheduled
        // write until the next change.
        removeMessages(MSG_WRITE_STATUS);

        FileOutputStream fos = null;
        try {
            fos = mStatusFile.startWrite();
            Parcel out = Parcel.obtain();
            final int N = mSyncStatus.size();
            for (int i=0; i<N; i++) {
                SyncStatusInfo status = mSyncStatus.valueAt(i);
                out.writeInt(STATUS_FILE_ITEM);
                status.writeToParcel(out, 0);
            }
            out.writeInt(STATUS_FILE_END);
            fos.write(out.marshall());
            out.recycle();

            mStatusFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Log.w(TAG, "Error writing status", e1);
            if (fos != null) {
                mStatusFile.failWrite(fos);
            }
        }
    }

    public static final int PENDING_OPERATION_VERSION = 1;

    /**
     * Read all pending operations back in to the initial engine state.
     */
    private void readPendingOperationsLocked() {
        if (DEBUG_FILE) Log.v(TAG, "Reading " + mPendingFile.getBaseFile());
        try {
            byte[] data = mPendingFile.readFully();
            Parcel in = Parcel.obtain();
            in.unmarshall(data, 0, data.length);
            in.setDataPosition(0);
            final int SIZE = in.dataSize();
            while (in.dataPosition() < SIZE) {
                int version = in.readInt();
                if (version != PENDING_OPERATION_VERSION) {
                    Log.w(TAG, "Unknown pending operation version "
                            + version + "; dropping all ops");
                    break;
                }
                int authorityId = in.readInt();
                int syncSource = in.readInt();
                byte[] flatExtras = in.createByteArray();
                AuthorityInfo authority = mAuthorities.get(authorityId);
                if (authority != null) {
                    Bundle extras = null;
                    if (flatExtras != null) {
                        extras = unflattenBundle(flatExtras);
                    }
                    PendingOperation op = new PendingOperation(
                            authority.account, syncSource,
                            authority.authority, extras);
                    op.authorityId = authorityId;
                    op.flatExtras = flatExtras;
                    if (DEBUG_FILE) Log.v(TAG, "Adding pending op: account=" + op.account
                            + " auth=" + op.authority
                            + " src=" + op.syncSource
                            + " extras=" + op.extras);
                    mPendingOperations.add(op);
                }
            }
        } catch (java.io.IOException e) {
            Log.i(TAG, "No initial pending operations");
        }
    }

    private void writePendingOperationLocked(PendingOperation op, Parcel out) {
        out.writeInt(PENDING_OPERATION_VERSION);
        out.writeInt(op.authorityId);
        out.writeInt(op.syncSource);
        if (op.flatExtras == null && op.extras != null) {
            op.flatExtras = flattenBundle(op.extras);
        }
        out.writeByteArray(op.flatExtras);
    }

    /**
     * Write all currently pending ops to the pending ops file.
     */
    private void writePendingOperationsLocked() {
        final int N = mPendingOperations.size();
        FileOutputStream fos = null;
        try {
            if (N == 0) {
                if (DEBUG_FILE) Log.v(TAG, "Truncating " + mPendingFile.getBaseFile());
                mPendingFile.truncate();
                return;
            }

            if (DEBUG_FILE) Log.v(TAG, "Writing new " + mPendingFile.getBaseFile());
            fos = mPendingFile.startWrite();

            Parcel out = Parcel.obtain();
            for (int i=0; i<N; i++) {
                PendingOperation op = mPendingOperations.get(i);
                writePendingOperationLocked(op, out);
            }
            fos.write(out.marshall());
            out.recycle();

            mPendingFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Log.w(TAG, "Error writing pending operations", e1);
            if (fos != null) {
                mPendingFile.failWrite(fos);
            }
        }
    }

    /**
     * Append the given operation to the pending ops file; if unable to,
     * write all pending ops.
     */
    private void appendPendingOperationLocked(PendingOperation op) {
        if (DEBUG_FILE) Log.v(TAG, "Appending to " + mPendingFile.getBaseFile());
        FileOutputStream fos = null;
        try {
            fos = mPendingFile.openAppend();
        } catch (java.io.IOException e) {
            if (DEBUG_FILE) Log.v(TAG, "Failed append; writing full file");
            writePendingOperationsLocked();
            return;
        }

        try {
            Parcel out = Parcel.obtain();
            writePendingOperationLocked(op, out);
            fos.write(out.marshall());
            out.recycle();
        } catch (java.io.IOException e1) {
            Log.w(TAG, "Error writing pending operations", e1);
        } finally {
            try {
                fos.close();
            } catch (java.io.IOException e2) {
            }
        }
    }

    static private byte[] flattenBundle(Bundle bundle) {
        byte[] flatData = null;
        Parcel parcel = Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            flatData = parcel.marshall();
        } finally {
            parcel.recycle();
        }
        return flatData;
    }

    static private Bundle unflattenBundle(byte[] flatData) {
        Bundle bundle;
        Parcel parcel = Parcel.obtain();
        try {
            parcel.unmarshall(flatData, 0, flatData.length);
            parcel.setDataPosition(0);
            bundle = parcel.readBundle();
        } catch (RuntimeException e) {
            // A RuntimeException is thrown if we were unable to parse the parcel.
            // Create an empty parcel in this case.
            bundle = new Bundle();
        } finally {
            parcel.recycle();
        }
        return bundle;
    }

    public static final int STATISTICS_FILE_END = 0;
    public static final int STATISTICS_FILE_ITEM_OLD = 100;
    public static final int STATISTICS_FILE_ITEM = 101;

    /**
     * Read all sync statistics back in to the initial engine state.
     */
    private void readStatisticsLocked() {
        try {
            byte[] data = mStatisticsFile.readFully();
            Parcel in = Parcel.obtain();
            in.unmarshall(data, 0, data.length);
            in.setDataPosition(0);
            int token;
            int index = 0;
            while ((token=in.readInt()) != STATISTICS_FILE_END) {
                if (token == STATISTICS_FILE_ITEM
                        || token == STATISTICS_FILE_ITEM_OLD) {
                    int day = in.readInt();
                    if (token == STATISTICS_FILE_ITEM_OLD) {
                        day = day - 2009 + 14245;  // Magic!
                    }
                    DayStats ds = new DayStats(day);
                    ds.successCount = in.readInt();
                    ds.successTime = in.readLong();
                    ds.failureCount = in.readInt();
                    ds.failureTime = in.readLong();
                    if (index < mDayStats.length) {
                        mDayStats[index] = ds;
                        index++;
                    }
                } else {
                    // Ooops.
                    Log.w(TAG, "Unknown stats token: " + token);
                    break;
                }
            }
        } catch (java.io.IOException e) {
            Log.i(TAG, "No initial statistics");
        }
    }

    /**
     * Write all sync statistics to the sync status file.
     */
    private void writeStatisticsLocked() {
        if (DEBUG_FILE) Log.v(TAG, "Writing new " + mStatisticsFile.getBaseFile());

        // The file is being written, so we don't need to have a scheduled
        // write until the next change.
        removeMessages(MSG_WRITE_STATISTICS);

        FileOutputStream fos = null;
        try {
            fos = mStatisticsFile.startWrite();
            Parcel out = Parcel.obtain();
            final int N = mDayStats.length;
            for (int i=0; i<N; i++) {
                DayStats ds = mDayStats[i];
                if (ds == null) {
                    break;
                }
                out.writeInt(STATISTICS_FILE_ITEM);
                out.writeInt(ds.day);
                out.writeInt(ds.successCount);
                out.writeLong(ds.successTime);
                out.writeInt(ds.failureCount);
                out.writeLong(ds.failureTime);
            }
            out.writeInt(STATISTICS_FILE_END);
            fos.write(out.marshall());
            out.recycle();

            mStatisticsFile.finishWrite(fos);
        } catch (java.io.IOException e1) {
            Log.w(TAG, "Error writing stats", e1);
            if (fos != null) {
                mStatisticsFile.failWrite(fos);
            }
        }
    }
}
