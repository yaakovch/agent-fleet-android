package com.termux.terminal;

import java.nio.charset.StandardCharsets;

public class FragmentedOutputTest extends TerminalTestCase {
    public void testEveryByteBoundaryPreservesUnicodeAnsiAndCompleteFileLink() {
        String sequence = "\033[31mשלום 😀\033[0m\r\n\033]8;;file:///tmp/report%20with%20spaces.pdf\033\\Open report\033]8;;\033\\";
        byte[] bytes = sequence.getBytes(StandardCharsets.UTF_8);
        for (int split = 1; split < bytes.length; split++) {
            withTerminalSized(80, 24);
            byte[] first = java.util.Arrays.copyOfRange(bytes, 0, split);
            byte[] second = java.util.Arrays.copyOfRange(bytes, split, bytes.length);
            mTerminal.append(first, first.length); mTerminal.append(second, second.length);
            assertTrue(mTerminal.getScreen().getTranscriptText().contains("שלום 😀"));
            assertEquals("file:///tmp/report%20with%20spaces.pdf", mTerminal.getScreen().getHyperlinkAt(1, 3));
        }
    }
}
