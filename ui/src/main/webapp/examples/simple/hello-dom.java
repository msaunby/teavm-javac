import org.teavm.jso.dom.html.*;

public class Main {
    public static void main(String[] args) {
        HTMLDocument document = HTMLDocument.current();
        HTMLElement elem = document.createElement("p").withText("Hello, web!");
        document.getBody().appendChild(elem);
    }
}
