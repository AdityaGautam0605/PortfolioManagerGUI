package portfolioManagerGUI;

/** A failure with a message suitable for display, without credentials or raw responses. */
public class OperationException extends RuntimeException {
    public OperationException(String message) {
        super(message);
    }

    public OperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
