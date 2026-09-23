// The one screen of the Android build, and the shortest possible bridge from an
// Activity to the desktop entry point.
//
// It will not work. JRock.main builds a javax.swing.JFrame, and there is no Swing
// on Android, so this module does not even reach a device: javac fails first, on
// JRock.java's own imports. The call is here so that, once somebody does start a
// native port, the thing to replace is a single line rather than a guess.
//
// Default package, like JRock itself, so the two files sit in the same namespace
// and no import is needed.

import android.app.Activity;
import android.os.Bundle;

public final class JRockActivity extends Activity {

    // Hands the Activity straight to the desktop main. On a real port this is where
    // the Android UI would be inflated instead, and JRock's model - the log, the
    // includes, the Bedrock client - would be driven from it.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        JRock.main(new String[0]);
    }
}
