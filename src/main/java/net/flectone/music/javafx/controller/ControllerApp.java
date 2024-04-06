package net.flectone.music.javafx.controller;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import lombok.Getter;
import net.flectone.music.Main;
import net.flectone.music.util.CookieUtils;
import net.flectone.music.util.FileUtils;
import netscape.javascript.JSException;
import netscape.javascript.JSObject;

import java.io.IOException;
import java.net.URL;
import java.util.LinkedList;
import java.util.Queue;
import java.util.ResourceBundle;

@Getter
public class ControllerApp implements Initializable {

    // JavaScript scripts constants
    private static final String PLAY_SCRIPT = "if (!externalAPI.isPlaying()) {externalAPI.togglePause();}";
    private static final String PAUSE_SCRIPT = "if (externalAPI.isPlaying()) {externalAPI.togglePause();}";
    private static final String NEXT_SCRIPT = "if (!externalAPI.isPlaying()) {externalAPI.togglePause();} else {externalAPI.next();}";
    private static final String CLICK_SCRIPT = "externalAPI.togglePause();";
    private static final String AUTO_SONG_INJECT_SCRIPT = "externalAPI.on(externalAPI.EVENT_STATE, () => setTimeout(() => {if (externalAPI.getProgress().position == 0.0) {externalAPI.next();}}, 1500));";

    private static final String GET_TIME_SCRIPT = "document.querySelector('.ytp-time-duration').textContent";
    private static final String GET_MEDIA_NAME_SCRIPT = "document.getElementsByClassName('style-scope ytd-watch-metadata')[0].firstElementChild.textContent;";
    private static final String GET_SONG_NAME_SCRIPT = "externalAPI.getCurrentTrack().title;";
    private static final String MEDIA_INJECT_SCRIPT = "document.querySelector('video').addEventListener('ended', e => {window.media.playNext(true)});";
    private static final String AUTO_MEDIA_INJECT_SCRIPT = "setTimeout(() => {if (document.querySelector('.ytp-time-current').textContent == \"0:00\") {document.querySelector('.ytp-cued-thumbnail-overlay').click();}}, 1500)";

    // UI elements
    @FXML
    WebView mediaWebView;
    @FXML
    WebView playerWebView;
    @FXML
    Button playerButton;
    @FXML
    Button skipButton;
    @FXML
    Button settingButton;
    @FXML
    TextField playerTextField;
    @FXML
    ListView<String> mediaQueueList;

    // WebEngines for WebView
    private WebEngine playerWebEngine;
    private WebEngine mediaWebEngine;

    // MediaBridge instance
    private MediaBridge mediaBridge;

    // Queue for media URLs
    private final Queue<String> mediaQueue = new LinkedList<>();

    @Override
    public void initialize(URL url, ResourceBundle resourceBundle) {
        CookieUtils.loadCookies();
        setupPlayer();
        setupMedia();
    }

    private void setupPlayer() {
        playerTextField.setText("https://music.yandex.ru/home");
        playerWebEngine = playerWebView.getEngine();
        playerWebEngine.setJavaScriptEnabled(true);

        playerWebEngine.getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == Worker.State.SUCCEEDED) {
                playerWebEngine.executeScript(AUTO_SONG_INJECT_SCRIPT);
                CookieUtils.saveCookies();
            }
        });
        loadPage();
    }

    private void setupMedia() {
        mediaWebEngine = mediaWebView.getEngine();
        mediaWebEngine.setJavaScriptEnabled(true);

        mediaWebEngine.getLoadWorker().stateProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == Worker.State.SUCCEEDED) {
                try {
                    injectScript(mediaWebEngine);
                } catch (Exception ignored) {}
            }
        });

        mediaBridge = new MediaBridge(this, mediaWebEngine, mediaQueue);

        FileUtils.load(mediaQueue);

        if (mediaQueue.isEmpty()) {
            mediaWebEngine.load(null);
        } else {
            mediaBridge.playNext(false);
        }
    }

    // Inject JavaScript into the web page
    private void injectScript(WebEngine webEngine) {
        System.out.println("Successful script injection");

        // Setting up MediaBridge for communication between Java and JavaScript
        JSObject jsobj = (JSObject) webEngine.executeScript("window");
        mediaBridge = new MediaBridge(this, mediaWebEngine, mediaQueue);
        jsobj.setMember("media", mediaBridge);

        // check video time
        String[] stringTime = String.valueOf(webEngine.executeScript(GET_TIME_SCRIPT)).split(":");
        int time = Integer.parseInt(stringTime[0]) * 60 + Integer.parseInt(stringTime[1]);
        if (time > 250) {
            mediaBridge.playNext(true);
            return;
        }

        webEngine.executeScript(MEDIA_INJECT_SCRIPT);
        webEngine.executeScript(AUTO_MEDIA_INJECT_SCRIPT);
    }

    // Inner class to bridge between Java and JavaScript
    public static class MediaBridge {
        private final ControllerApp controllerApp;
        private final WebEngine mediaWebEngine;
        private final Queue<String> mediaQueue;

        public MediaBridge(ControllerApp controllerApp, WebEngine mediaWebEngine, Queue<String> mediaQueue) {
            this.controllerApp = controllerApp;
            this.mediaWebEngine = mediaWebEngine;
            this.mediaQueue = mediaQueue;
        }

        // Play next media, optionally skipping the current one
        public void playNext(boolean skip) {
            Platform.runLater(() -> {

                String media = controllerApp.getMediaName();
                if (!skip && media != null) return;

                System.out.println(media);

                media = mediaQueue.poll();
                if (media == null) {
                    if (skip) {
                        controllerApp.nextPlayer();
                    } else {
                        controllerApp.playPlayer();
                    }

                    mediaWebEngine.load(null);
                    return;
                }

                mediaWebEngine.load(media);
                controllerApp.pausePlayer();
            });
        }
    }

    public void loadPage() {
        playerWebEngine.load(playerTextField.getText());
    }

    public void playPlayer() {
        try {
            playerWebEngine.executeScript(PLAY_SCRIPT);
        } catch (JSException e) {
            e.printStackTrace();
        }
    }

    public void pausePlayer() {
        try {
            playerWebEngine.executeScript(PAUSE_SCRIPT);
        } catch (JSException e) {
            e.printStackTrace();
        }
    }

    public void nextPlayer() {
        try {
            playerWebEngine.executeScript(NEXT_SCRIPT);
        } catch (JSException e) {
            e.printStackTrace();
        }
    }

    // Add music to media queue
    public void addMusic(String url) {
        Platform.runLater(() -> {
            System.out.println(url);
            mediaQueue.add(url);
            updateTestList();
            mediaBridge.playNext(false);
        });
    }

    // Skip media
    public void mediaSkipButtonAction() {
        mediaBridge.playNext(true);
        updateTestList();
    }

    // Click player
    public void clickPlayer() {
        playerWebEngine.executeScript(CLICK_SCRIPT);
    }

    private String getMediaName() {
        try {
            Object object = mediaWebEngine.executeScript(GET_MEDIA_NAME_SCRIPT);
            if (object != null) return String.valueOf(object).trim();
        } catch (JSException ignored) {}

        return null;
    }

    // Get current track name
    public String getCurrentTrackName() {
        String string = getMediaName();

        return string != null
                ? string
                : String.valueOf(playerWebEngine.executeScript(GET_SONG_NAME_SCRIPT)).trim();
    }

    private void updateTestList() {
        Platform.runLater(() -> {
            mediaQueueList.getItems().clear();
            mediaQueueList.getItems().addAll(mediaQueue);
        });
    }

    public void showSettingTwitch() throws IOException {
        Main.app.showTwitchScene();
    }

}
