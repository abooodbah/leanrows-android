# Data safety answers

Play Console > App content > Data safety. LeanRows has no internet
permission and no third-party code that collects data, so every answer is
"no".

| Question | Answer |
| --- | --- |
| Does your app collect or share any of the required user data types? | No |
| Is all of the user data collected by your app encrypted in transit? | Not applicable: no data is collected or sent. |
| Do you provide a way for users to request that their data is deleted? | Not applicable: no data is collected. |

Why "no" is accurate:

- The manifest requests no permissions. Without `android.permission.INTERNET`
  the app cannot open network connections. CI checks the release APK for
  this on every pull request (`aapt2 dump permissions`).
- The app does not use WebView or any other component that goes online.
- No analytics, crash reporting, advertising or account libraries are
  included. The only runtime library is AndroidX RecyclerView, which draws
  the list of rows and collects nothing.
- The file a user opens is read to show it and is not sent anywhere. When
  the app that shares a file only offers a stream, LeanRows copies it into
  its own cache folder so it can jump to any row; the copy is deleted from
  the folder as soon as it is open, and its space is freed when the file is
  closed. The app saves no settings and turns off Android backup
  (`android:allowBackup="false"`).

Data deletion: nothing to delete.
