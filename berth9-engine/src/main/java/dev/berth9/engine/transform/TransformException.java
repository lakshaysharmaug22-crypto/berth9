package dev.berth9.engine.transform;

/** A value could not be transformed; the message is shown to the operator as-is. */
public class TransformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TransformException(String message) {
        super(message);
    }
}
