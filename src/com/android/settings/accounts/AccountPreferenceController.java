/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.settings.accounts;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.v14.preference.PreferenceFragment;
import android.support.v7.preference.Preference;
import android.support.v7.preference.Preference.OnPreferenceClickListener;
import android.support.v7.preference.PreferenceGroup;
import android.support.v7.preference.PreferenceScreen;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settings.AccessiblePreferenceCategory;
import com.android.settings.DimmableIconPreference;
import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.Utils;
import com.android.settings.core.PreferenceController;
import com.android.settings.core.lifecycle.LifecycleObserver;
import com.android.settings.core.lifecycle.events.OnPause;
import com.android.settings.core.lifecycle.events.OnResume;
import com.android.settings.dashboard.DashboardFeatureProvider;
import com.android.settings.overlay.FeatureFactory;
import com.android.settings.search.Index;
import com.android.settings.search.SearchIndexableRaw;
import com.android.settingslib.RestrictedPreference;
import com.android.settingslib.accounts.AuthenticatorHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static android.content.Intent.EXTRA_USER;
import static android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS;
import static android.os.UserManager.DISALLOW_REMOVE_MANAGED_PROFILE;
import static android.os.UserManager.DISALLOW_REMOVE_USER;
import static android.provider.Settings.EXTRA_AUTHORITIES;

public class AccountPreferenceController extends PreferenceController
        implements AuthenticatorHelper.OnAccountsUpdateListener,
        OnPreferenceClickListener, LifecycleObserver, OnPause, OnResume {

    private static final String TAG = "AccountPrefController";
    private static final String ADD_ACCOUNT_ACTION = "android.settings.ADD_ACCOUNT_SETTINGS";

    private static final int ORDER_ACCOUNT_PROFILES = 1;
    private static final int ORDER_LAST = 1002;
    private static final int ORDER_NEXT_TO_LAST = 1001;
    private static final int ORDER_NEXT_TO_NEXT_TO_LAST = 1000;

    private UserManager mUm;
    private SparseArray<ProfileData> mProfiles = new SparseArray<ProfileData>();
    private ManagedProfileBroadcastReceiver mManagedProfileBroadcastReceiver
                = new ManagedProfileBroadcastReceiver();
    private Preference mProfileNotAvailablePreference;
    private String[] mAuthorities;
    private int mAuthoritiesCount = 0;
    private PreferenceFragment mParent;
    private boolean mIAEnabled;
    private int mAccountProfileOrder = ORDER_ACCOUNT_PROFILES;
    private AccountRestrictionHelper mHelper;
    private DashboardFeatureProvider mDashboardFeatureProvider;

    /**
     * Holds data related to the accounts belonging to one profile.
     */
    public static class ProfileData {
        /**
         * The preference that displays the accounts.
         */
        public PreferenceGroup preferenceGroup;
        /**
         * The preference that displays the add account button.
         */
        public DimmableIconPreference addAccountPreference;
        /**
         * The preference that displays the button to remove the managed profile
         */
        public RestrictedPreference removeWorkProfilePreference;
        /**
         * The preference that displays managed profile settings.
         */
        public Preference managedProfilePreference;
        /**
         * The {@link AuthenticatorHelper} that holds accounts data for this profile.
         */
        public AuthenticatorHelper authenticatorHelper;
        /**
         * The {@link UserInfo} of the profile.
         */
        public UserInfo userInfo;
    }

    public AccountPreferenceController(Context context, PreferenceFragment parent,
        String[] authorities) {
        this(context, parent, authorities, new AccountRestrictionHelper(context));
    }

    @VisibleForTesting
    AccountPreferenceController(Context context, PreferenceFragment parent,
        String[] authorities, AccountRestrictionHelper helper) {
        super(context);
        mUm = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mAuthorities = authorities;
        mParent = parent;
        if (mAuthorities != null) {
            mAuthoritiesCount = mAuthorities.length;
        }
        mDashboardFeatureProvider =
            FeatureFactory.getFactory(mContext).getDashboardFeatureProvider(mContext);
        mIAEnabled = mDashboardFeatureProvider.isEnabled();
        mHelper = helper;
    }

    @Override
    public boolean isAvailable() {
        return !mUm.isManagedProfile();
    }

    @Override
    public String getPreferenceKey() {
        return null;
    }

    @Override
    public void updateRawDataToIndex(List<SearchIndexableRaw> rawData) {
        if (!isAvailable()) {
            return;
        }
        final Resources res = mContext.getResources();
        final String screenTitle = res.getString(R.string.account_settings_title);

        List<UserInfo> profiles = mUm.getProfiles(UserHandle.myUserId());
        final int profilesCount = profiles.size();
        for (int i = 0; i < profilesCount; i++) {
            UserInfo userInfo = profiles.get(i);
            if (userInfo.isEnabled()) {
                if (!mHelper.hasBaseUserRestriction(DISALLOW_MODIFY_ACCOUNTS, userInfo.id)) {
                    SearchIndexableRaw data = new SearchIndexableRaw(mContext);
                    data.title = res.getString(R.string.add_account_label);
                    data.screenTitle = screenTitle;
                    rawData.add(data);
                }
                if (userInfo.isManagedProfile()) {
                    if (!mHelper.hasBaseUserRestriction(DISALLOW_REMOVE_MANAGED_PROFILE,
                        UserHandle.myUserId())) {
                        SearchIndexableRaw data = new SearchIndexableRaw(mContext);
                        data.title = res.getString(R.string.remove_managed_profile_label);
                        data.screenTitle = screenTitle;
                        rawData.add(data);
                    }
                    {
                        SearchIndexableRaw data = new SearchIndexableRaw(mContext);
                        data.title = res.getString(R.string.managed_profile_settings_title);
                        data.screenTitle = screenTitle;
                        rawData.add(data);
                    }
                }
            }
        }
    }

    @Override
    public void onResume() {
        cleanUpPreferences();
        updateUi();
        mManagedProfileBroadcastReceiver.register(mContext);
        listenToAccountUpdates();
    }

    @Override
    public void onPause() {
        stopListeningToAccountUpdates();
        mManagedProfileBroadcastReceiver.unregister(mContext);
    }

    @Override
    public void onAccountsUpdate(UserHandle userHandle) {
        final ProfileData profileData = mProfiles.get(userHandle.getIdentifier());
        if (profileData != null) {
            updateAccountTypes(profileData);
        } else {
            Log.w(TAG, "Missing Settings screen for: " + userHandle.getIdentifier());
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        // Check the preference
        final int count = mProfiles.size();
        for (int i = 0; i < count; i++) {
            ProfileData profileData = mProfiles.valueAt(i);
            if (preference == profileData.addAccountPreference) {
                Intent intent = new Intent(ADD_ACCOUNT_ACTION);
                intent.putExtra(EXTRA_USER, profileData.userInfo.getUserHandle());
                intent.putExtra(EXTRA_AUTHORITIES, mAuthorities);
                mContext.startActivity(intent);
                return true;
            }
            if (preference == profileData.removeWorkProfilePreference) {
                final int userId = profileData.userInfo.id;
                RemoveUserFragment.newInstance(userId).show(mParent.getFragmentManager(),
                        "removeUser");
                return true;
            }
            if (preference == profileData.managedProfilePreference) {
                Bundle arguments = new Bundle();
                arguments.putParcelable(Intent.EXTRA_USER, profileData.userInfo.getUserHandle());
                ((SettingsActivity) mParent.getActivity()).startPreferencePanel(
                        ManagedProfileSettings.class.getName(), arguments,
                        R.string.managed_profile_settings_title, null, null, 0);
                return true;
            }
        }
        return false;
    }

    SparseArray<ProfileData> getProfileData() {
        return mProfiles;
    }

    private void updateUi() {
        if (!isAvailable()) {
            // This should not happen
            Log.e(TAG, "We should not be showing settings for a managed profile");
            if (!mIAEnabled) {
                ((AccountSettings) mParent).finish();
            }
            return;
        }

        if (!mIAEnabled) {
            // Load the preferences from an XML resource
            mParent.addPreferencesFromResource(R.xml.account_settings);
        }

        if (mUm.isLinkedUser()) {
            // Restricted user or similar
            UserInfo userInfo = mUm.getUserInfo(UserHandle.myUserId());
            updateProfileUi(userInfo);
        } else {
            List<UserInfo> profiles = mUm.getProfiles(UserHandle.myUserId());
            final int profilesCount = profiles.size();
            for (int i = 0; i < profilesCount; i++) {
                updateProfileUi(profiles.get(i));
            }
        }

        // Add all preferences, starting with one for the primary profile.
        // Note that we're relying on the ordering given by the SparseArray keys, and on the
        // value of UserHandle.USER_OWNER being smaller than all the rest.
        final int profilesCount = mProfiles.size();
        for (int i = 0; i < profilesCount; i++) {
            updateAccountTypes(mProfiles.valueAt(i));
        }
    }

    private void updateProfileUi(final UserInfo userInfo) {
        final Context context = mContext;
        final ProfileData profileData = new ProfileData();
        profileData.userInfo = userInfo;
        AccessiblePreferenceCategory preferenceGroup =
            mHelper.createAccessiblePreferenceCategory(mParent.getPreferenceManager().getContext());
        preferenceGroup.setOrder(mAccountProfileOrder++);
        if (isSingleProfile()) {
            preferenceGroup.setTitle(R.string.account_for_section_header);
            preferenceGroup.setContentDescription(
                mContext.getString(R.string.account_settings));
        } else if (userInfo.isManagedProfile()) {
            preferenceGroup.setTitle(R.string.category_work);
            String workGroupSummary = getWorkGroupSummary(context, userInfo);
            preferenceGroup.setSummary(workGroupSummary);
            preferenceGroup.setContentDescription(
                mContext.getString(R.string.accessibility_category_work, workGroupSummary));
            profileData.removeWorkProfilePreference = newRemoveWorkProfilePreference(context);
            mHelper.enforceRestrictionOnPreference(profileData.removeWorkProfilePreference,
                DISALLOW_REMOVE_MANAGED_PROFILE, UserHandle.myUserId());
            profileData.managedProfilePreference = newManagedProfileSettings();
        } else {
            preferenceGroup.setTitle(R.string.category_personal);
            preferenceGroup.setContentDescription(
                mContext.getString(R.string.accessibility_category_personal));
        }
        mParent.getPreferenceScreen().addPreference(preferenceGroup);
        profileData.preferenceGroup = preferenceGroup;
        if (userInfo.isEnabled()) {
            profileData.authenticatorHelper = new AuthenticatorHelper(context,
                    userInfo.getUserHandle(), this);
            profileData.addAccountPreference = newAddAccountPreference(context);
            mHelper.enforceRestrictionOnPreference(profileData.addAccountPreference,
                DISALLOW_MODIFY_ACCOUNTS, userInfo.id);
        }
        mProfiles.put(userInfo.id, profileData);
        Index.getInstance(mContext).updateFromClassNameResource(
                AccountSettings.class.getName(), true, true);
    }

    private DimmableIconPreference newAddAccountPreference(Context context) {
        DimmableIconPreference preference =
            new DimmableIconPreference(mParent.getPreferenceManager().getContext());
        preference.setTitle(R.string.add_account_label);
        preference.setIcon(R.drawable.ic_menu_add);
        preference.setOnPreferenceClickListener(this);
        preference.setOrder(ORDER_NEXT_TO_NEXT_TO_LAST);
        return preference;
    }

    private RestrictedPreference newRemoveWorkProfilePreference(Context context) {
        RestrictedPreference preference = new RestrictedPreference(
            mParent.getPreferenceManager().getContext());
        preference.setTitle(R.string.remove_managed_profile_label);
        preference.setIcon(R.drawable.ic_menu_delete);
        preference.setOnPreferenceClickListener(this);
        preference.setOrder(ORDER_LAST);
        return preference;
    }


    private Preference newManagedProfileSettings() {
        Preference preference = new Preference(mParent.getPreferenceManager().getContext());
        preference.setTitle(R.string.managed_profile_settings_title);
        preference.setIcon(R.drawable.ic_settings);
        preference.setOnPreferenceClickListener(this);
        preference.setOrder(ORDER_NEXT_TO_LAST);
        return preference;
    }

    private String getWorkGroupSummary(Context context, UserInfo userInfo) {
        PackageManager packageManager = context.getPackageManager();
        ApplicationInfo adminApplicationInfo = Utils.getAdminApplicationInfo(context, userInfo.id);
        if (adminApplicationInfo == null) {
            return null;
        }
        CharSequence appLabel = packageManager.getApplicationLabel(adminApplicationInfo);
        return mContext.getString(R.string.managing_admin, appLabel);
    }

    void cleanUpPreferences() {
        PreferenceScreen screen = mParent.getPreferenceScreen();
        for (int i = 0; i < mProfiles.size(); i++) {
            final PreferenceGroup preferenceGroup = mProfiles.valueAt(i).preferenceGroup;
            screen.removePreference(preferenceGroup);
        }
        mProfiles.clear();
        mAccountProfileOrder = ORDER_ACCOUNT_PROFILES;
    }

    private void listenToAccountUpdates() {
        final int count = mProfiles.size();
        for (int i = 0; i < count; i++) {
            AuthenticatorHelper authenticatorHelper = mProfiles.valueAt(i).authenticatorHelper;
            if (authenticatorHelper != null) {
                authenticatorHelper.listenToAccountUpdates();
            }
        }
    }

    private void stopListeningToAccountUpdates() {
        final int count = mProfiles.size();
        for (int i = 0; i < count; i++) {
            AuthenticatorHelper authenticatorHelper = mProfiles.valueAt(i).authenticatorHelper;
            if (authenticatorHelper != null) {
                authenticatorHelper.stopListeningToAccountUpdates();
            }
        }
    }

    private void updateAccountTypes(ProfileData profileData) {
        profileData.preferenceGroup.removeAll();
        if (profileData.userInfo.isEnabled()) {
            final ArrayList<AccountTypePreference> preferences = getAccountTypePreferences(
                    profileData.authenticatorHelper, profileData.userInfo.getUserHandle());
            final int count = preferences.size();
            for (int i = 0; i < count; i++) {
                profileData.preferenceGroup.addPreference(preferences.get(i));
            }
            if (profileData.addAccountPreference != null) {
                profileData.preferenceGroup.addPreference(profileData.addAccountPreference);
            }
        } else {
            // Put a label instead of the accounts list
            if (mProfileNotAvailablePreference == null) {
                mProfileNotAvailablePreference =
                    new Preference(mParent.getPreferenceManager().getContext());
            }
            mProfileNotAvailablePreference.setEnabled(false);
            mProfileNotAvailablePreference.setIcon(R.drawable.empty_icon);
            mProfileNotAvailablePreference.setTitle(null);
            mProfileNotAvailablePreference.setSummary(
                    R.string.managed_profile_not_available_label);
            profileData.preferenceGroup.addPreference(mProfileNotAvailablePreference);
        }
        if (profileData.removeWorkProfilePreference != null) {
            profileData.preferenceGroup.addPreference(profileData.removeWorkProfilePreference);
        }
        if (profileData.managedProfilePreference != null) {
            profileData.preferenceGroup.addPreference(profileData.managedProfilePreference);
        }
    }

    private ArrayList<AccountTypePreference> getAccountTypePreferences(AuthenticatorHelper helper,
            UserHandle userHandle) {
        final String[] accountTypes = helper.getEnabledAccountTypes();
        final ArrayList<AccountTypePreference> accountTypePreferences =
                new ArrayList<AccountTypePreference>(accountTypes.length);

        for (int i = 0; i < accountTypes.length; i++) {
            final String accountType = accountTypes[i];
            // Skip showing any account that does not have any of the requested authorities
            if (!accountTypeHasAnyRequestedAuthorities(helper, accountType)) {
                continue;
            }
            final CharSequence label = helper.getLabelForType(mContext, accountType);
            if (label == null) {
                continue;
            }
            final String titleResPackageName = helper.getPackageForType(accountType);
            final int titleResId = helper.getLabelIdForType(accountType);

            final Account[] accounts = AccountManager.get(mContext)
                    .getAccountsByTypeAsUser(accountType, userHandle);
            final boolean skipToAccount = accounts.length == 1
                    && !helper.hasAccountPreferences(accountType);
            final Drawable icon = helper.getDrawableForType(mContext, accountType);
            final Context prefContext = mParent.getPreferenceManager().getContext();

            if (mIAEnabled) {
                // Add a preference row for each individual account
                for (Account account : accounts) {
                    final ArrayList<String> auths =
                        helper.getAuthoritiesForAccountType(account.type);
                    if (!AccountRestrictionHelper.showAccount(mAuthorities, auths)) {
                        continue;
                    }
                    final Bundle fragmentArguments = new Bundle();
                    fragmentArguments.putParcelable(AccountDetailDashboardFragment.KEY_ACCOUNT,
                        account);
                    fragmentArguments.putParcelable(AccountDetailDashboardFragment.KEY_USER_HANDLE,
                        userHandle);
                    fragmentArguments.putString(AccountDetailDashboardFragment.KEY_ACCOUNT_TYPE,
                        accountType);
                    fragmentArguments.putString(AccountDetailDashboardFragment.KEY_ACCOUNT_LABEL,
                        label.toString());
                    fragmentArguments.putInt(AccountDetailDashboardFragment.KEY_ACCOUNT_TITLE_RES,
                        titleResId);
                    fragmentArguments.putParcelable(EXTRA_USER, userHandle);
                    accountTypePreferences.add(new AccountTypePreference(
                        prefContext, account.name, titleResPackageName, titleResId, label,
                        AccountDetailDashboardFragment.class.getName(), fragmentArguments, icon));
                }
            } else if (skipToAccount) {
                final Bundle fragmentArguments = new Bundle();
                fragmentArguments.putParcelable(AccountSyncSettings.ACCOUNT_KEY,
                        accounts[0]);
                fragmentArguments.putParcelable(EXTRA_USER, userHandle);

                accountTypePreferences.add(new AccountTypePreference(
                        prefContext, label, titleResPackageName,
                    titleResId, AccountSyncSettings.class.getName(), fragmentArguments, icon));
            } else {
                final Bundle fragmentArguments = new Bundle();
                fragmentArguments.putString(ManageAccountsSettings.KEY_ACCOUNT_TYPE, accountType);
                fragmentArguments.putString(ManageAccountsSettings.KEY_ACCOUNT_LABEL,
                        label.toString());
                fragmentArguments.putParcelable(EXTRA_USER, userHandle);

                accountTypePreferences.add(new AccountTypePreference(

                        prefContext, label, titleResPackageName,
                    titleResId, ManageAccountsSettings.class.getName(), fragmentArguments, icon));
            }
            helper.preloadDrawableForType(mContext, accountType);
        }
        // Sort by label
        Collections.sort(accountTypePreferences, new Comparator<AccountTypePreference>() {
            @Override
            public int compare(AccountTypePreference t1, AccountTypePreference t2) {
                int result = 0;
                if (mIAEnabled) {
                    result = t1.getSummary().toString().compareTo(t2.getSummary().toString());
                }
                return result != 0
                    ? result : t1.getTitle().toString().compareTo(t2.getTitle().toString());
            }
        });
        return accountTypePreferences;
    }

    private boolean accountTypeHasAnyRequestedAuthorities(AuthenticatorHelper helper,
            String accountType) {
        if (mAuthoritiesCount == 0) {
            // No authorities required
            return true;
        }
        final ArrayList<String> authoritiesForType = helper.getAuthoritiesForAccountType(
                accountType);
        if (authoritiesForType == null) {
            Log.d(TAG, "No sync authorities for account type: " + accountType);
            return false;
        }
        for (int j = 0; j < mAuthoritiesCount; j++) {
            if (authoritiesForType.contains(mAuthorities[j])) {
                return true;
            }
        }
        return false;
    }

    private boolean isSingleProfile() {
        return mUm.isLinkedUser() || mUm.getProfiles(UserHandle.myUserId()).size() == 1;
    }

    private class ManagedProfileBroadcastReceiver extends BroadcastReceiver {
        private boolean mListeningToManagedProfileEvents;

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            Log.v(TAG, "Received broadcast: " + action);
            if (action.equals(Intent.ACTION_MANAGED_PROFILE_REMOVED)
                    || action.equals(Intent.ACTION_MANAGED_PROFILE_ADDED)) {
                // Clean old state
                stopListeningToAccountUpdates();
                cleanUpPreferences();
                // Build new state
                updateUi();
                listenToAccountUpdates();
                return;
            }
            Log.w(TAG, "Cannot handle received broadcast: " + intent.getAction());
        }

        public void register(Context context) {
            if (!mListeningToManagedProfileEvents) {
                IntentFilter intentFilter = new IntentFilter();
                intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
                intentFilter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
                context.registerReceiver(this, intentFilter);
                mListeningToManagedProfileEvents = true;
            }
        }

        public void unregister(Context context) {
            if (mListeningToManagedProfileEvents) {
                context.unregisterReceiver(this);
                mListeningToManagedProfileEvents = false;
            }
        }
    }
}
