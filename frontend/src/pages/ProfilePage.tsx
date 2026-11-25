import React, { useState } from 'react';
import { useAuthStore } from '../stores/authStore';
import { useMutation } from '@apollo/client';
import { UPDATE_PROFILE_MUTATION, CHANGE_PASSWORD_MUTATION } from '../graphql/operations';
import { Input } from '../components/ui/Input';
import { Textarea } from '../components/ui/Textarea';
import { toast } from 'react-hot-toast';
import { PageHeader } from '@/components/layout/PageHeader';
import { cn, normalizeTimestamp } from '@/lib/utils';
import { User, Mail, Calendar, Shield, Key, Save, Loader2, Edit2, Lock } from 'lucide-react';

const ProfilePage: React.FC = () => {
  const { user, setUser } = useAuthStore();
  const [isEditingProfile, setIsEditingProfile] = useState(false);
  const [isChangingPassword, setIsChangingPassword] = useState(false);

  const [profileData, setProfileData] = useState({
    username: user?.username || '',
    email: user?.email || '',
    displayName: user?.displayName || '',
    bio: user?.bio || ''
  });

  const [passwordData, setPasswordData] = useState({
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  });

  const [profileError, setProfileError] = useState<string | null>(null);
  const [passwordErrors, setPasswordErrors] = useState<Partial<Record<keyof typeof passwordData, string>>>({});
  const [passwordError, setPasswordError] = useState<string | null>(null);

  const [updateProfile, { loading: updateLoading }] = useMutation(UPDATE_PROFILE_MUTATION, {
    onCompleted: (data) => {
      if (data.updateProfile) {
        setUser(data.updateProfile);
        toast.success('Profile updated successfully');
        setIsEditingProfile(false);
      }
    },
    onError: (error) => {
      setProfileError(error.message || 'Unable to save your profile. Please try again.');
    }
  });

  const [changePassword, { loading: passwordLoading }] = useMutation(CHANGE_PASSWORD_MUTATION, {
    onCompleted: () => {
      toast.success('Password changed successfully');
      setPasswordData({ currentPassword: '', newPassword: '', confirmPassword: '' });
      setIsChangingPassword(false);
    },
    onError: (error) => {
      setPasswordError(error.message);
    }
  });

  const handleProfileSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (updateLoading) return;
    setProfileError(null);
    void updateProfile({ variables: { input: { ...profileData, username: profileData.username.trim(), email: profileData.email.trim(), displayName: profileData.displayName.trim() } } }).catch(() => {});
  };

  const handlePasswordSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    if (passwordLoading) return;
    setPasswordError(null);
    const nextErrors: typeof passwordErrors = {};
    if (!passwordData.currentPassword) nextErrors.currentPassword = 'Enter your current password.';
    if (passwordData.newPassword.length < 8) nextErrors.newPassword = 'Use at least 8 characters for your new password.';
    if (!passwordData.confirmPassword || passwordData.newPassword !== passwordData.confirmPassword) nextErrors.confirmPassword = 'Enter the same new password again.';
    setPasswordErrors(nextErrors);
    if (Object.keys(nextErrors).length) {
      (e.currentTarget as HTMLFormElement).querySelector<HTMLInputElement>(`[name="${Object.keys(nextErrors)[0]}"]`)?.focus();
      return;
    }
    void changePassword({ variables: { oldPassword: passwordData.currentPassword, newPassword: passwordData.newPassword } }).catch(() => {});
  };

  if (!user) return null;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <PageHeader
        title={user.displayName || user.username}
        description={user.email}
        measure="max-w-4xl"
        lead={
          <span
            className="border-border bg-accent text-foreground flex h-10 w-10 shrink-0 items-center justify-center rounded-lg border text-base font-semibold"
            aria-hidden="true"
          >
            {(user.displayName || user.username || 'U').slice(0, 1).toUpperCase()}
          </span>
        }
      />

      <div className="custom-scrollbar flex-1 overflow-y-auto px-4 py-6 sm:px-6 sm:py-8">
        <div className="mx-auto max-w-4xl">
          {/* Profile Information */}
          <div className={cn(
            'rounded-2xl border border-border bg-card p-4 mb-4 sm:p-6 sm:mb-6'
          )}>
            <div className="flex flex-wrap items-center justify-between gap-3 mb-4 sm:mb-6">
              <div className="flex items-center gap-3">
                <div className={cn(
                  'flex h-10 w-10 items-center justify-center rounded-xl',
                  'bg-primary/10 text-primary'
                )}>
                  <User size={20} />
                </div>
                <h2 className="text-base font-semibold text-foreground">Profile Information</h2>
              </div>
              {!isEditingProfile && (
                <button
                  type="button"
                  onClick={() => setIsEditingProfile(true)}
                  className={cn(
                    'flex min-h-10 items-center gap-2 px-3 py-1.5 rounded-lg text-sm sm:min-h-0',
                    'text-primary hover:bg-primary/10 transition-colors duration-150'
                  )}
                >
                  <Edit2 size={14} />
                  Edit
                </button>
              )}
            </div>

            {isEditingProfile ? (
              <form onSubmit={handleProfileSubmit} aria-label="Edit profile" aria-busy={updateLoading} className="space-y-4">
                {profileError && <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm text-destructive">{profileError}</p>}
                <div>
                  <label htmlFor="profile-username" className="block text-sm font-medium text-foreground mb-1.5">
                    Username
                  </label>
                  <Input
                    id="profile-username"
                    name="username"
                    autoComplete="username"
                    disabled={updateLoading}
                    value={profileData.username}
                    readOnly
                    helperText="Your username cannot be changed."
                    leftIcon={<User size={16} />}
                  />
                </div>

                <div>
                  <label htmlFor="profile-email" className="block text-sm font-medium text-foreground mb-1.5">
                    Email
                  </label>
                  <Input
                    id="profile-email"
                    name="email"
                    autoComplete="email"
                    disabled={updateLoading}
                    type="email"
                    required
                    value={profileData.email}
                    onChange={(e) => setProfileData(prev => ({ ...prev, email: e.target.value }))}
                    leftIcon={<Mail size={16} />}
                  />
                </div>

                <div>
                  <label htmlFor="profile-displayName" className="block text-sm font-medium text-foreground mb-1.5">
                    Display Name
                  </label>
                  <Input
                    id="profile-displayName"
                    name="displayName"
                    autoComplete="name"
                    autoCapitalize="words"
                    disabled={updateLoading}
                    value={profileData.displayName}
                    onChange={(e) => setProfileData(prev => ({ ...prev, displayName: e.target.value }))}
                    placeholder="How should we call you?"
                  />
                </div>

                <div>
                  <label htmlFor="profile-bio" className="block text-sm font-medium text-foreground mb-1.5">
                    Bio
                  </label>
                  <Textarea
                    id="profile-bio"
                    name="bio"
                    disabled={updateLoading}
                    value={profileData.bio}
                    onChange={(event) => setProfileData(previous => ({ ...previous, bio: event.target.value }))}
                    rows={4}
                    placeholder="Tell us about yourself"
                  />
                </div>

                <div className="flex flex-wrap gap-3 pt-2">
                  <button
                    type="submit"
                    disabled={updateLoading}
                    className={cn(
                      'inline-flex min-h-11 flex-1 items-center justify-center gap-2 whitespace-nowrap px-4 py-2.5 rounded-xl text-sm font-medium sm:min-h-0 sm:flex-none',
                      'bg-primary text-primary-foreground hover:bg-primary-hover',
                      'disabled:opacity-50 transition-colors duration-150'
                    )}
                  >
                    {updateLoading ? (
                      <>
                        <Loader2 className="animate-spin" size={16} />
                        Saving...
                      </>
                    ) : (
                      <>
                        <Save size={16} />
                        Save Changes
                      </>
                    )}
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setIsEditingProfile(false);
                      setProfileData({
                        username: user.username || user.email || '',
                        email: user.email || '',
                        displayName: user.displayName || '',
                        bio: user.bio || ''
                      });
                    }}
                    className={cn(
                      'min-h-11 flex-1 px-4 py-2.5 rounded-xl text-sm font-medium sm:min-h-0 sm:flex-none',
                      'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
                      'transition-colors duration-150'
                    )}
                  >
                    Cancel
                  </button>
                </div>
              </form>
            ) : (
              <dl className="divide-border/60 divide-y">
                <div className="flex items-center gap-3 py-3">
                  <User size={16} className="text-muted-foreground shrink-0" aria-hidden="true" />
                  <div className="min-w-0">
                    <dt className="text-muted-foreground text-2xs font-medium tracking-wider uppercase">Username</dt>
                    <dd className="text-foreground text-sm break-all">{user.username || user.email}</dd>
                  </div>
                </div>

                <div className="flex items-center gap-3 py-3">
                  <Mail size={16} className="text-muted-foreground shrink-0" aria-hidden="true" />
                  <div className="min-w-0">
                    <dt className="text-muted-foreground text-2xs font-medium tracking-wider uppercase">Email</dt>
                    <dd className="text-foreground text-sm break-all">{user.email}</dd>
                  </div>
                </div>

                <div className="flex items-center gap-3 py-3">
                  <User size={16} className="text-muted-foreground shrink-0" aria-hidden="true" />
                  <div className="min-w-0">
                    <dt className="text-muted-foreground text-2xs font-medium tracking-wider uppercase">Display Name</dt>
                    <dd className="text-foreground text-sm break-words">{user.displayName || '-'}</dd>
                  </div>
                </div>

                <div className="flex items-center gap-3 py-3">
                  <Calendar size={16} className="text-muted-foreground shrink-0" aria-hidden="true" />
                  <div>
                    <dt className="text-muted-foreground text-2xs font-medium tracking-wider uppercase">Member Since</dt>
                    <dd className="text-foreground text-sm">
                      {user.createdAt ? new Date(normalizeTimestamp(user.createdAt)).toLocaleDateString() : '-'}
                    </dd>
                  </div>
                </div>

                <div className="flex items-center gap-3 py-3">
                  <Shield size={16} className="text-muted-foreground shrink-0" aria-hidden="true" />
                  <div>
                    <dt className="text-muted-foreground text-2xs font-medium tracking-wider uppercase">Role</dt>
                    <dd className="text-foreground text-sm capitalize">{user.role.toLowerCase()}</dd>
                  </div>
                </div>

                {user.bio && (
                  <div className="py-3">
                    <dt className="text-muted-foreground text-2xs font-medium tracking-wider uppercase mb-1">Bio</dt>
                    <dd className="text-foreground text-sm break-words whitespace-pre-line">{user.bio}</dd>
                  </div>
                )}
              </dl>
            )}
          </div>

          {/* Security */}
          <div className={cn(
            'rounded-2xl border border-border bg-card p-4 sm:p-6'
          )}>
            <div className="flex flex-wrap items-center justify-between gap-3 mb-4 sm:mb-6">
              <div className="flex items-center gap-3">
                <div className={cn(
                  'flex h-10 w-10 items-center justify-center rounded-xl',
                  'bg-warning/10 text-warning'
                )}>
                  <Lock size={20} />
                </div>
                <h2 className="text-base font-semibold text-foreground">Security</h2>
              </div>
              {!isChangingPassword && (
                <button
                  type="button"
                  onClick={() => setIsChangingPassword(true)}
                  className={cn(
                    'flex min-h-10 items-center gap-2 px-3 py-1.5 rounded-lg text-sm sm:min-h-0',
                    'text-primary hover:bg-primary/10 transition-colors duration-150'
                  )}
                >
                  <Key size={14} />
                  Change Password
                </button>
              )}
            </div>

            {isChangingPassword ? (
              <form onSubmit={handlePasswordSubmit} noValidate aria-label="Change password" aria-busy={passwordLoading} className="space-y-4">
                <p className="text-xs text-muted-foreground">All password fields are required.</p>
                {passwordError && <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm text-destructive">{passwordError}</p>}
                <div>
                  <label htmlFor="profile-currentPassword" className="block text-sm font-medium text-foreground mb-1.5">
                    Current Password
                  </label>
                  <Input
                    id="profile-currentPassword"
                    name="currentPassword"
                    autoComplete="current-password"
                    disabled={passwordLoading}
                    error={passwordErrors.currentPassword}
                    type="password"
                    value={passwordData.currentPassword}
                    onChange={(e) => { setPasswordData(prev => ({ ...prev, currentPassword: e.target.value })); setPasswordErrors(prev => ({ ...prev, currentPassword: undefined })); }}
                    required
                    leftIcon={<Key size={16} />}
                  />
                </div>

                <div>
                  <label htmlFor="profile-newPassword" className="block text-sm font-medium text-foreground mb-1.5">
                    New Password
                  </label>
                  <Input
                    helperText="Use at least 8 characters. A longer password is stronger."
                    id="profile-newPassword"
                    name="newPassword"
                    autoComplete="new-password"
                    disabled={passwordLoading}
                    error={passwordErrors.newPassword}
                    type="password"
                    value={passwordData.newPassword}
                    onChange={(e) => { setPasswordData(prev => ({ ...prev, newPassword: e.target.value })); setPasswordErrors(prev => ({ ...prev, newPassword: undefined })); }}
                    required
                    leftIcon={<Key size={16} />}
                  />
                </div>

                <div>
                  <label htmlFor="profile-confirmPassword" className="block text-sm font-medium text-foreground mb-1.5">
                    Confirm New Password
                  </label>
                  <Input
                    id="profile-confirmPassword"
                    name="confirmPassword"
                    autoComplete="new-password"
                    disabled={passwordLoading}
                    error={passwordErrors.confirmPassword}
                    type="password"
                    value={passwordData.confirmPassword}
                    onChange={(e) => { setPasswordData(prev => ({ ...prev, confirmPassword: e.target.value })); setPasswordErrors(prev => ({ ...prev, confirmPassword: undefined })); }}
                    required
                    leftIcon={<Key size={16} />}
                  />
                </div>

                <div className="flex flex-wrap gap-3 pt-2">
                  <button
                    type="submit"
                    disabled={passwordLoading}
                    className={cn(
                      'inline-flex min-h-11 flex-1 items-center justify-center gap-2 whitespace-nowrap px-4 py-2.5 rounded-xl text-sm font-medium sm:min-h-0 sm:flex-none',
                      'bg-primary text-primary-foreground hover:bg-primary-hover',
                      'disabled:opacity-50 transition-colors duration-150'
                    )}
                  >
                    {passwordLoading ? (
                      <>
                        <Loader2 className="animate-spin" size={16} />
                        Changing...
                      </>
                    ) : (
                      'Change Password'
                    )}
                  </button>

                  <button
                    type="button"
                    onClick={() => {
                      setIsChangingPassword(false);
                      setPasswordData({ currentPassword: '', newPassword: '', confirmPassword: '' });
                    }}
                    className={cn(
                      'min-h-11 flex-1 px-4 py-2.5 rounded-xl text-sm font-medium sm:min-h-0 sm:flex-none',
                      'bg-muted text-muted-foreground hover:bg-accent hover:text-foreground',
                      'transition-colors duration-150'
                    )}
                  >
                    Cancel
                  </button>
                </div>
              </form>
            ) : (
              <p className="text-sm text-muted-foreground">
                Keep your account secure by using a strong password. We recommend using a combination of letters, numbers, and symbols.
              </p>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ProfilePage;
