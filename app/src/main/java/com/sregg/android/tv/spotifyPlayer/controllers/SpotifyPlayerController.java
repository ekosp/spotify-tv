package com.sregg.android.tv.spotifyPlayer.controllers;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.crashlytics.android.answers.Answers;
import com.crashlytics.android.answers.CustomEvent;
import com.spotify.sdk.android.player.ConnectionStateCallback;
import com.spotify.sdk.android.player.PlaybackBitrate;
import com.spotify.sdk.android.player.Player;
import com.spotify.sdk.android.player.PlayerNotificationCallback;
import com.spotify.sdk.android.player.PlayerState;
import com.spotify.sdk.android.player.PlayerStateCallback;
import com.spotify.sdk.android.player.Spotify;
import com.squareup.picasso.Picasso;
import com.sregg.android.tv.spotifyPlayer.BusProvider;
import com.sregg.android.tv.spotifyPlayer.Constants;
import com.sregg.android.tv.spotifyPlayer.SpotifyTvApplication;
import com.sregg.android.tv.spotifyPlayer.enums.Control;
import com.sregg.android.tv.spotifyPlayer.events.ContentState;
import com.sregg.android.tv.spotifyPlayer.events.OnPause;
import com.sregg.android.tv.spotifyPlayer.events.OnPlay;
import com.sregg.android.tv.spotifyPlayer.events.OnShuffleChanged;
import com.sregg.android.tv.spotifyPlayer.events.OnTrackChanged;
import com.sregg.android.tv.spotifyPlayer.events.PlayerStateChanged;
import com.sregg.android.tv.spotifyPlayer.settings.UserPreferences;
import com.sregg.android.tv.spotifyPlayer.utils.Utils;

import java.io.IOException;
import java.util.List;

import kaaes.spotify.webapi.android.SpotifyService;
import kaaes.spotify.webapi.android.models.Track;
import kaaes.spotify.webapi.android.models.TrackSimple;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;
import timber.log.Timber;


/**
 * <p>Plays tracks, playlists and albums into the Spotify SDK</p>
 * <p>Fires OTTO playback events like {@link com.sregg.android.tv.spotifyPlayer.events.OnPlay}, {@link com.sregg.android.tv.spotifyPlayer.events.OnPause}, etc..</p>
 * <p>Also updates the Now Playing Card in the Home Screen</p>
 */
public class SpotifyPlayerController implements PlayerNotificationCallback, ConnectionStateCallback, AudioManager.OnAudioFocusChangeListener {

    public static final String TAG = "SpotifyPlayerController";

    private static final int SKIP_DURATION_MS = 10 * 1000;

    private final Player mPlayer;
    private final MediaSession mNowPlayingSession;
    private final SpotifyService mSpotifyService;
    private final Handler mHandler;

    private ContentState mContentState;

    private boolean mIsShuffleOn = false;
    private final AudioManager audioManager;

    public SpotifyPlayerController(Player player, SpotifyService spotifyService) {
        Context context = SpotifyTvApplication.getInstance().getApplicationContext();

        mHandler = new Handler(context.getMainLooper());

        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mPlayer = player;

        mPlayer.addPlayerNotificationCallback(this);
        mPlayer.addConnectionStateCallback(this);
        setPlayerBitrate(UserPreferences.getInstance(context).getBitrate());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mNowPlayingSession = new MediaSession(context, TAG);
            mNowPlayingSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

            // for the MediaBrowserService
            //setSessionToken(mNowPlayingSession.getSessionToken());
        } else {
            mNowPlayingSession = null;
        }

        mSpotifyService = spotifyService;

        // init playing state with dummy data
        resetPlayingState();
    }

    public void resetPlayingState() {
        mContentState = new ContentState("", "", null, null);
    }

    public void play(String currentObjectUri, List<String> trackUris, List<TrackSimple> tracks) {
        if (!SpotifyTvApplication.isCurrentUserPremium()) {
            Toast.makeText(SpotifyTvApplication.getInstance().getApplicationContext(), "You need a premium Spotify account to play music on this app", Toast.LENGTH_SHORT).show();
        } else {
            mContentState = new ContentState(currentObjectUri, trackUris.get(0), trackUris, tracks);

            if (requestAudioFocus()) {
                mPlayer.play(trackUris);
                startNowPlayingSession();
            }
        }
    }

    private boolean requestAudioFocus() {
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void loseAudioFocus(){
        audioManager.abandonAudioFocus(this);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startNowPlayingSession() {
        if (mNowPlayingSession != null && !mNowPlayingSession.isActive()) {
            mNowPlayingSession.setActive(true);
            mNowPlayingSession.setCallback(new MediaButtonReceiver(this));
        }
    }


    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void stopNowPlayingSession() {
        if (mNowPlayingSession != null && mNowPlayingSession.isActive()) {
            mNowPlayingSession.setActive(false);
            mNowPlayingSession.setCallback(null);
        }
    }

    public void togglePauseResume() {
        mPlayer.getPlayerState(new PlayerStateCallback() {
            @Override
            public void onPlayerState(PlayerState playerState) {
                if (playerState.playing) {
                    mPlayer.pause();
                    loseAudioFocus();
                } else if (requestAudioFocus()){
                    mPlayer.resume();
                }
            }
        });
    }

    public
    @Nullable
    ContentState getPlayingState() {
        return mContentState;
    }

    public void getPlayerState(@NonNull PlayerStateCallback callback) {
        mPlayer.getPlayerState(callback);
    }

    public boolean isShuffleOn() {
        return mIsShuffleOn;
    }

    public void terminate() {
        stopNowPlayingSession();
        Spotify.destroyPlayer(mPlayer);
    }

    @Override
    public void onPlaybackEvent(EventType eventType, PlayerState playerState) {
        Log.d(TAG, String.format("%s - isPlaying: %s - trackUri: %s - positionInMs:%s",
                eventType.name(), playerState.playing, playerState.trackUri, playerState.positionInMs));

        mContentState.setCurrentTrackUri(playerState.trackUri);

        switch (eventType) {
            case PLAY:
                BusProvider.post(new OnPlay(mContentState));
                Answers.getInstance().logCustom(new CustomEvent(Constants.ANSWERS_EVENT_PLAYER_PLAY));
                break;
            case PAUSE:
                BusProvider.post(new OnPause(mContentState));
                Answers.getInstance().logCustom(new CustomEvent(Constants.ANSWERS_EVENT_PLAYER_PAUSE));
                loseAudioFocus();
                break;
            case TRACK_CHANGED:
                trackNowPlayingTrack(playerState.trackUri);
                Answers.getInstance().logCustom(new CustomEvent(Constants.ANSWERS_EVENT_PLAYER_TRACK_CHANGE));
                break;
            case END_OF_CONTEXT:
                stopNowPlayingSession();
                resetPlayingState();
                loseAudioFocus();
                break;
        }

        BusProvider.post(new PlayerStateChanged(playerState, mContentState));
    }

    private void trackNowPlayingTrack(String currentTrackUri) {
        // load track from id (from uri)
        mSpotifyService.getTrack(Utils.getIdFromUri(currentTrackUri), new Callback<Track>() {
            @Override
            public void success(final Track track, Response response) {
                mContentState.setCurrentTrack(track);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        updateNowPlayingMetadata(track);
                        trackLastFm(track);

                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                BusProvider.post(new OnTrackChanged(mContentState));
                            }
                        });
                    }
                }).start();
            }

            @Override
            public void failure(RetrofitError error) {

            }
        });
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void updateNowPlayingMetadata(Track track) {
        if (mNowPlayingSession == null) {
            return;
        }

        MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder();

        // track title
        metadataBuilder.putString(MediaMetadata.METADATA_KEY_TITLE,
                track.name);

        // artists
        metadataBuilder.putString(MediaMetadata.METADATA_KEY_ARTIST,
                Utils.getTrackArtists(track));

        // album picture
        try {
            Bitmap bitmap = Picasso.with(SpotifyTvApplication.getInstance().getApplicationContext())
                    .load(track.album.images.get(0).url)
                    .get();
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART,
                    bitmap);
        } catch (IOException e) {
            Log.e(TAG, "Error downloading track picture for now playing card", e);
        }

        mNowPlayingSession.setMetadata(metadataBuilder.build());
    }

    private void trackLastFm(final Track track) {
        try {
            LastFmApi.getInstance().scrobbleSpotifyTrack(track);
        } catch (Exception e) {
            // know issue in de.umass.lastfm.Track.Track.parseIntoScrobbleResult()
            Log.w(TAG, "Error while scrobbling to last.fm", e);
        }
    }

    @Override
    public void onPlaybackError(ErrorType errorType, String s) {

    }

    @Override
    public void onLoggedIn() {

    }

    @Override
    public void onLoggedOut() {

    }

    @Override
    public void onLoginFailed(Throwable throwable) {

    }

    @Override
    public void onTemporaryError() {

    }

    @Override
    public void onConnectionMessage(String s) {

    }

    public void onControlClick(Control control) {
        switch (control) {
            case PLAY:
                if (requestAudioFocus()) {
                    mPlayer.resume();
                }
                break;
            case PAUSE:
                mPlayer.pause();
                loseAudioFocus();
                break;
            case NEXT:
                mPlayer.skipToNext();
                break;
            case PREVIOUS:
                mPlayer.skipToPrevious();
                break;
            case STOP:
                stopPlayer();
                break;
            case SHUFFLE:
                mIsShuffleOn = !mIsShuffleOn;
                mPlayer.setShuffle(mIsShuffleOn);
                BusProvider.post(new OnShuffleChanged(mIsShuffleOn));

                // reload current object if not null
                if (!TextUtils.isEmpty(mContentState.getCurrentObjectUri())) {
                    play(mContentState.getCurrentObjectUri(), mContentState.getTrackUrisQueue(), mContentState.getTracksQueue());
                }
                break;
            case FAST_FORWARD:
                mPlayer.getPlayerState(new PlayerStateCallback() {
                    @Override
                    public void onPlayerState(PlayerState playerState) {
                        final int fastForwardPosition = playerState.positionInMs + SKIP_DURATION_MS;
                        if (fastForwardPosition < playerState.durationInMs) {
                            mPlayer.seekToPosition(fastForwardPosition);
                        } else {
                            mPlayer.skipToNext();
                        }
                    }
                });
                break;
            case REWIND:
                mPlayer.getPlayerState(new PlayerStateCallback() {
                    @Override
                    public void onPlayerState(PlayerState playerState) {
                        final int currentPosition = playerState.positionInMs;
                        if (currentPosition < SKIP_DURATION_MS) {
                            mPlayer.seekToPosition(0);
                        } else {
                            final int rewindPosition = currentPosition - SKIP_DURATION_MS;
                            mPlayer.seekToPosition(rewindPosition);
                        }
                    }
                });
                break;
            default:
                break;
        }
    }

    private void stopPlayer() {
        stopNowPlayingSession();
        if (mPlayer != null) {
            mPlayer.pause();
            mPlayer.clearQueue();
            resetPlayingState();
        }
        loseAudioFocus();
        BusProvider.post(new OnTrackChanged(mContentState));
    }

    public void setPlayerBitrate(PlaybackBitrate selectedBitrate) {
        if (mPlayer != null) {
            mPlayer.setPlaybackBitrate(selectedBitrate);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Timber.d("audio focus gained");
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                Timber.d("audio focus lossed");
                stopPlayer();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Timber.d("audio focus lossed transient");
                if (mPlayer != null) {
                    mPlayer.pause();
                }
                break;
        }
    }
}
