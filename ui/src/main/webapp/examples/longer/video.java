import org.teavm.jso.browser.*;
import org.teavm.jso.dom.html.*;
import org.teavm.jso.dom.events.*;

public class Main {
    private static String[] types = { "video/mp4", "video/webm", "video/ogg" };
    private static String[] extensions = { "mp4", "webm", "ogv" };
    private HTMLDocument document = HTMLDocument.current();
    private HTMLVideoElement video;
    private HTMLElement controls;
    private HTMLButtonElement playButton;
    private HTMLElement playButtonIcon;
    private HTMLElement progressIndicator;
    private HTMLElement progressIcon;
    private int controlsTimerId = -1;
    private boolean dragging;
    private int dragX;

    private Main(String baseUrl) {  
        initWindow();
        video = document.createElement("video").cast();
        video.setAttribute("width", "100%");
        video.setAttribute("height", "100%");
        initSources(baseUrl);

        HTMLElement container = document.createElement("div");
        container.getStyle().setProperty("position", "absolute");
        container.getStyle().setProperty("width", "100%");
        container.getStyle().setProperty("height", "100%");
        container.appendChild(video);
        document.getBody().appendChild(container);

        initControls();
        video.load();

        touchControls();
        video.addEventListener("mousemove", e -> touchControls(), true);
        video.addEventListener("play", e -> updateControlsState());
        video.addEventListener("pause", e -> updateControlsState());
        video.addEventListener("timeupdate", e -> updateControlsState());
    }

    private void initWindow() {
        document.getBody().getStyle().setProperty("overflow", "hidden");
        HTMLLinkElement link = document.createElement("link").cast();
        link.setRel("stylesheet");
        link.setHref("https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/3.3.7/css/bootstrap.min.css");
        document.getHead().appendChild(link);
    }

    private void initSources(String baseUrl) {
        for (int i = 0; i < types.length; ++i) {
            HTMLSourceElement source = document.createElement("source").cast();
        	source.setSrc(baseUrl + "." + extensions[i]);
        	source.setAttribute("type", types[i]);
            video.appendChild(source);
        }
        video.appendChild(document.createElement("div")
                .withText("Playing video is not supported in your browser"));
    }

    private void initControls() {
        controls = document.createElement("div");
        document.getBody().appendChild(controls);

        controls.getStyle().setProperty("position", "absolute");
        controls.getStyle().setProperty("bottom", "0");
        controls.getStyle().setProperty("width", "100%");
        controls.getStyle().setProperty("height", "40px");
        controls.getStyle().setProperty("background-color", "rgba(230,230,230,0.7)");

        initPlayButton();
        initProgressIndicator();
        updateControlsState();
    }

    private void touchControls() {
        showControls();
        preventHidingControls();
        controlsTimerId = Window.setTimeout(() -> hideControls(), 5000);
    }

    private void preventHidingControls() {
        if (controlsTimerId >= 0) {
            Window.clearTimeout(controlsTimerId);
        }
        controlsTimerId = -1;
    }

    private void showControls() {
        controls.getStyle().setProperty("display", "block");
    }

    private void hideControls() {
        controls.getStyle().setProperty("display", "none");
    }

    private void initPlayButton() {
        playButton = document.createElement("button").cast();
        playButton.setClassName("btn btn-default");
        playButtonIcon = document.createElement("span");
        playButton.appendChild(playButtonIcon);
        playButton.getStyle().setProperty("position", "absolute");
        playButton.getStyle().setProperty("height", "100%");
        playButton.getStyle().setProperty("width", "40px");

        playButton.listenClick(e -> {
            if (video.isPaused()) {
            	video.play();
            } else {
                video.pause();
            }
        });

        controls.appendChild(playButton);
    }

    private void initProgressIndicator() {
        progressIndicator = document.createElement("div").cast();
        progressIndicator.getStyle().setProperty("position", "absolute");
        progressIndicator.getStyle().setProperty("left", "55px");
        progressIndicator.getStyle().setProperty("right", "15px");
        progressIndicator.getStyle().setProperty("top", "13px");
        progressIndicator.getStyle().setProperty("bottom", "13px");        
        progressIndicator.getStyle().setProperty("border", "1px solid rgb(200,200,200)");
        progressIndicator.getStyle().setProperty("overflow", "show");

        progressIcon = document.createElement("div");
        progressIcon.getStyle().setProperty("border-radius", "50%");
        progressIcon.getStyle().setProperty("background-color", "black");
        progressIcon.getStyle().setProperty("position", "absolute");
        progressIcon.getStyle().setProperty("width", "20px");
        progressIcon.getStyle().setProperty("height", "20px");
        progressIcon.getStyle().setProperty("top", "50%");
        progressIcon.getStyle().setProperty("margin-top", "-10px");
        progressIcon.getStyle().setProperty("margin-left", "-10px");
        progressIcon.getStyle().setProperty("cursor", "pointer");

        progressIndicator.appendChild(progressIcon);

        controls.appendChild(progressIndicator);

        progressIcon.addEventListener("mousedown", (MouseEvent e) -> {
            document.addEventListener("mousemove", dragListener, true);
            document.addEventListener("mouseup", dragEndListener, true);
            TextRectangle rect = progressIcon.getBoundingClientRect();
            dragX = e.getClientX() - rect.getLeft() - 10;
            e.preventDefault();
            preventHidingControls();
        }, true);

        dragEndListener = e -> {
            document.removeEventListener("mousemove", dragListener, true);
            document.removeEventListener("mouseup", dragEndListener, true);
            dragging = false;
            video.setCurrentTime(getNewProgress(e) * video.getDuration());
            e.preventDefault();
            touchControls();
        };
        dragListener = e -> {
            dragging = true;
            progressIcon.getStyle().setProperty("left", renderProgress(getNewProgress(e)));
            e.preventDefault();
        };
    }

    private double getNewProgress(MouseEvent event) {
        TextRectangle rect = progressIndicator.getBoundingClientRect();
        int offset = event.getClientX() - dragX - rect.getLeft();
        return Math.max(0, Math.min(1, offset / (double) rect.getWidth()));
    }

    private EventListener<MouseEvent> dragEndListener;
    private EventListener<MouseEvent> dragListener;

    private void updateControlsState() {
        playButtonIcon.setClassName("glyphicon glyphicon-" + (video.isPaused() ? "play" : "pause"));

        if (!dragging) {
            String progress = renderProgress(video.getCurrentTime() / video.getDuration());
            progressIcon.getStyle().setProperty("left", progress);
        }
    }

    private static String renderProgress(double progress) {
        progress = ((int) (progress * 10000)) / 100.0;
        return progress + "%";
    }

    public static void main(String[] args) {
        new Main("http://media.w3.org/2010/05/sintel/trailer");
    }
}
