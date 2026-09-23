package dev.berth9.engine.edi;

/** The input is not an X12 interchange at all (as opposed to one with structural issues). */
public class X12Exception extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public X12Exception(String message) {
        super(message);
    }
}
