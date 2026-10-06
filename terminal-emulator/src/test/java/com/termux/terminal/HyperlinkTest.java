package com.termux.terminal;

public class HyperlinkTest extends TerminalTestCase {
    private void link(String target, String label) { enterString("\033]8;;" + target + "\033\\" + label + "\033]8;;\033\\"); }

    public void testLabelRetainsTargetThroughScrollAndResize() {
        withTerminalSized(12, 4);
        link("file:///tmp/report.pdf", "Open report");
        assertEquals("file:///tmp/report.pdf", mTerminal.getScreen().getHyperlinkAt(0, 5));
        enterString("\r\nnext\r\nnext\r\nnext\r\nlast");
        assertEquals("file:///tmp/report.pdf", mTerminal.getScreen().getHyperlinkAt(-1, 5));
        mTerminal.resize(20, 4, INITIAL_CELL_WIDTH_PIXELS, INITIAL_CELL_HEIGHT_PIXELS);
        boolean retained = false;
        for (int row = -mTerminal.getScreen().getActiveTranscriptRows(); row < 4; row++) for (int col = 0; col < 20; col++)
            retained |= "file:///tmp/report.pdf".equals(mTerminal.getScreen().getHyperlinkAt(row, col));
        assertTrue(retained);
    }

    public void testOverwriteAndEraseRemoveOldLinkAndCopiesRetainIt() {
        withTerminalSized(12, 4);
        link("/tmp/a.txt", "label");
        mTerminal.getScreen().blockCopy(0, 0, 5, 1, 2, 1);
        assertEquals("/tmp/a.txt", mTerminal.getScreen().getHyperlinkAt(1, 3));
        enterString("\rX");
        assertNull(mTerminal.getScreen().getHyperlinkAt(0, 0));
        assertEquals("/tmp/a.txt", mTerminal.getScreen().getHyperlinkAt(0, 1));
        enterString("\033[2J");
        assertNull(mTerminal.getScreen().getHyperlinkAt(0, 1));
        assertNull(mTerminal.getScreen().getHyperlinkAt(1, 3));
    }

    public void testWideCombiningWrapAndAlternateBufferKeepExactCells() {
        withTerminalSized(6, 4);
        link("/tmp/wide.txt", "a\u0301界abcd");
        assertEquals("/tmp/wide.txt", mTerminal.getScreen().getHyperlinkAt(0, 1));
        assertEquals("/tmp/wide.txt", mTerminal.getScreen().getHyperlinkAt(0, 2));
        assertEquals("/tmp/wide.txt", mTerminal.getScreen().getHyperlinkAt(1, 0));
        enterString("\033[?1049h");
        assertNull(mTerminal.getScreen().getHyperlinkAt(0, 0));
        link("/tmp/alt.txt", "alt");
        enterString("\033[?1049l");
        assertEquals("/tmp/wide.txt", mTerminal.getScreen().getHyperlinkAt(0, 0));
    }

    public void testClosingAndResetStopFutureCellsAndRejectOversizeTarget() {
        withTerminalSized(12, 4);
        link("/tmp/a", "a");
        enterString("plain");
        assertNull(mTerminal.getScreen().getHyperlinkAt(0, 1));
        enterString("\033]8;;/tmp/reset\007");
        mTerminal.reset();
        enterString("x");
        assertNull(mTerminal.getScreen().getHyperlinkAt(0, 6));
        link("/" + new String(new char[2100]).replace('\0', 'x'), "z");
        assertNull(mTerminal.getScreen().getHyperlinkAt(0, 7));
    }
}
