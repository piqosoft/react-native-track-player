---
sidebar_position: 96
---

# Android Auto Support

Make sure to read through [Google's guidelines](https://developer.android.com/training/cars/media) before adding Android Auto support to your RNTP app! Not all features in the article are implemented so PRs are always welcome. Also do note this lib has migrated to media3 and Android Auto still refers to the exoplayer2 implementation. For any questions please [harass the media3 team](https://github.com/androidx/media/issues) instead.

See the example app and [Podverse's PR](https://github.com/podverse/podverse-rn/pull/1928) as examples adding Android Auto support to an existing RNTP app.

## RN Version Compatibility

HeadlessJsTaskService.java does change across RN versions, for example from RN 0.71. RNTP's HeadlessJsTaskService will use whatever compatible with the most recent RN version. You may need to manually edit HeadlessJsMediaService.java to make it compatible with your current RN version. See [Podverse's RNTP fork](https://github.com/lovegaoshi/react-native-track-player/tree/dev-podverse-aa) that uses RN 0.66.

I can only guarantee specifically this lib's compatibility is to whatever [APM](https://github.com/lovegaoshi/azusa-player-mobile) uses, which is almost always only the newest.

## Necessary Declarations

Enable Google voice assistant permissions (must be implemented per Google's AA guidelines) in your project's AndroidManifest.xml by adding this below:

```
            <intent-filter>
                <action android:name="android.media.action.MEDIA_PLAY_FROM_SEARCH" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
```

Then add Android Auto declarations as so:

```
        <meta-data android:name="com.google.android.gms.car.application"
            android:resource="@xml/automotive_app_desc"/>
```

Make `automotive_app_desc.xml` under your project's `android/src/main/res/xml` with the content below:

```
<automotiveApp>
    <uses name="media"/>
</automotiveApp>
```

Lastly enable any app to show in Android Auto via Settings -> Apps -> Android Auto -> In-App Notification Settings -> vertical dot on the top right corner -> Developer Settings -> Unknown Sources. This is necessary for any app not in the play store yet.

This will immediately enable your RNTP app to have synced media control in Android Auto.

## Content Hierarchy

Android Auto can show `playable` (songs) and `browsable` (playlists) `MediaItem`s. These MediaItems are organized like a tree structure, starting from the root branching out. In addition, `browsable` MediaItems at the root level will become tabs shown at the top. AA supports a maximum of 4 tabs.

`MediaItem` must have a `Title`, `mediaID` and `playable` field. In addition, `Subtitle` (artist) and `iconUri` (album art) can be specified.

AA content hierarchy is a dict with `mediaID`s as keys and a list of `MediaItem`s as values. See the demo hierarchy here.

## Fetching Contents

Contents can be refreshed by repeatly calling TrackPlayer.setBrowseTree. Sometimes you do not want to load your contents all at once to the browseTree, say because it's quite heavy on internet connections. You may programmatically load data via `Event.RemoteBrowse` that returns the browsable mediaItem's `mediaID` a user has clicked. Then you can update content as above and AA will work as so. See Podverse.

Because content refresh triggers MediaBrowserService.notifyChildrenChange, this effectively triggers RemoteBrowse again; there is evidence all tabs are triggered this way, not leaves. so if you do not set a caching system to prevent this from loading, add a debounce check. But do not use a debounce hook. See Podverse.

## Event Callback

Android Auto requires 3 events to be handled: `Event.RemotePlayId` `Event.RemotePlaySearch` and `Event.RemoteSkip`. `Event.RemotePlayId` is emitted when users click playable items in AA's content hierarchy. `Event.RemotePlaySearch` is emitted when users use google voice assistant in Android Auto, which must be implemented per Google's AA guidelines. `Event.RemoteSkip` is responsible for playback from users clicking in the "queue" list from the top right corner. This should be handled as `TrackPlayer.skip(event.index).then(() => TrackPlayer.play());`.

## Headless Start

Since media3, MusicService is now automatically started by the system/MediaLibraryService and allows a better headless implementation. This may be applicable to the old exoplayer2 implementation, but I'm not bothered to check. What's important is now MusicService will wake the js entry point, executes everything in index.js, but do NOT render the view. Thus any registered callbacks work (eg. registerHeadlessTask), but hooks do not.

For a clean headless start implementation, the app initialization and specifically PlayFromSearch and PlayFromMediaId events have to be handled outside of a hook. Please refer to (APM's PR)[https://github.com/lovegaoshi/azusa-player-mobile/pull/566] for an example.

Below is the old wake Activity implementation.

Android Auto only starts the MediaBrowserCompactService, not the activity of your app. RNTP itself does separate its Service (MusicService) from Activity (RN App), however because RNTP has to be initialized in the RN side, technically it is not possible to start Android Auto without starting the RN Activity. With a native android app, one would refactor all logic into MusicService and leave Activity only showing the UI, but this is not possible for RN apps. There are workarounds:

You must enable some way to start activity from a background service. The easiest one is to enable android.permission.SYSTEM_ALERT_WINDOW and ask your users to enable "draw over other apps." This will trigger the app to start when RNActivity is either isDestoryed() or not exist, and the service waking it is in the whitelist (android auto and systemui). Your app will be automatically brought to foreground.

You may also have noticed Android Activities cannot start when the screen is locked/off. This is an intended behavior of Android. The workaround is to enable in AndroidManifest.xml

```
            android:showOnLockScreen="true"
            android:turnScreenOn="true"
```

or add this in the onCreate function of RNActivity:

```
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true);
      setTurnScreenOn(true);
    }
```

This allows the RN Activity to overlay on top of the lock screen and start itself normally. However now the activity will always stay on top of the lock screen, meaning this app can be now used without unlocking. To work around this again, create a native module call to turn it off after your app is fully initialized.

```
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(false);
      setTurnScreenOn(false);
    }
```

## Album Art

I originally enabled album art via https://github.com/lovegaoshi/KotlinAudio/commit/7a3d90b5b7b548e45b8b54ffcf62eac5c795bc14 but [google's guidelines](https://developer.android.com/training/cars/media/#display-artwork) seem to contradict with that. nevertheless however RNTP is currently set up (for ex https://github.com/lovegaoshi/react-native-track-player/blob/6f634594f24aa1974b2c8cdc6848b8b349cccdf0/android/src/main/java/com/doublesymmetry/kotlinaudio/notification/NotificationManager.kt#L389) for remote urls it works great, but for local uris (file:///) and embedded covers within local media files it wont work.

for local uris while I do not have a use and no rigorous tests yet, I believe converting the file:/// uri to a content:// one, as specified in the google guidelines, would work. u can see how i did this via a fileProvider: https://github.com/lovegaoshi/azusa-player-mobile/pull/449

for embedded covers this has to be first resolved and written to a file, then load the content:// uri as in the google guidelines. I tried with both ffmpeg and MediaMetadataRetriever, opted for the latter in the end for simplicity. commit is here: https://github.com/lovegaoshi/react-native-track-player/commit/6f634594f24aa1974b2c8cdc6848b8b349cccdf0

the specific implementation I have does have a drawback taht the local file is written in the /Pictures folder. you might be able to write to cache using File() then convert to content:// with a fileProvider, but I chose the simplicity of MediaStore and can deal with this drawback.

## Known Issues

JumpForward and JumpBackward may not show on Android <13 devices. To resolve this, implement these buttons as CustomActions, just like how they are handled under Android >=13. You need to implement changes in KotlinAudio. Note this will introduce [duplicate custom actions buttons](https://github.com/doublesymmetry/react-native-track-player/issues/1970) so more patches are needed. See Podverse's KotlinAudio fork.

If [Event.RemoteBrowse is not firing for the first two tabs](https://github.com/lovegaoshi/react-native-track-player/issues/26), you may need to reinstall android auto.

## App Showcase

[APM](https://play.google.com/store/apps/details?id=com.noxplay.noxplayer) and [Podverse](https://play.google.com/store/apps/details?id=com.podverse) use a customized fork and are both on the google play store in production with auto support.

Getting pass Play Store's review process for an auto suppported app is tough. Here are a few issues we encountered:

```
Issue found: App doesn't perform as expected

Your app does not perform all functions properly or as expected from a user perspective.

For example, your app does not load media content in Android Auto.
```

This was due to APM never performed content updates via `TrackPlayer.setBrowseTree` (albeit initializes) thoughout the app's lifecycle. I think a human tester intervened and expected what my app does.

https://github.com/lovegaoshi/azusa-player-mobile/pull/209

```
something about media not responsive to voice commands
```

This was due to Podverse did not handle voice commands. Although play store refused to approve repeatly after fix until an appeal was submitted, then it was approved.

https://github.com/podverse/podverse-rn/pull/1969

```
your app does not respond after issuing a voice command.
```

This is a recent rejection I experienced since this year and it could be JS bugs I accidentally introduced or now a native handler to MEDIA_PLAY_FROM_SEARCH is expected; but for the PR that fixed this as a reference, see https://github.com/lovegaoshi/azusa-player-mobile/pull/407
