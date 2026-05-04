package com.wewatch.android.net;

public interface WeWatchSocketListener {
    void onOpen();

    void onMessage(String message);

    void onClosed();

    void onFailure(Exception error);
}
