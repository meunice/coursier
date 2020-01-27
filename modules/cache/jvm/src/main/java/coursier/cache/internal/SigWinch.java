package coursier.cache.internal;

import sun.misc.Signal;
import sun.misc.SignalHandler;

public class SigWinch {

  public static void addHandler(Runnable runnable) {

    SignalHandler handler = new SignalHandler() {
      @Override
      public void handle(Signal arg0) {
        runnable.run();
      }
    };

    Signal.handle(new Signal("WINCH"), handler);
  }

}
