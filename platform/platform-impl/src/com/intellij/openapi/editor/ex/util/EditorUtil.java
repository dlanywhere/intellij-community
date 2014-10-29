/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.editor.ex.util;

import com.intellij.diagnostic.Dumpable;
import com.intellij.diagnostic.LogMessageEx;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.ComplementaryFontsRegistry;
import com.intellij.openapi.editor.impl.EditorImpl;
import com.intellij.openapi.editor.impl.FontInfo;
import com.intellij.openapi.editor.impl.IterationState;
import com.intellij.openapi.fileEditor.impl.text.TextEditorImpl;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import org.intellij.lang.annotations.JdkConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Arrays;
import java.util.List;

public final class EditorUtil {
  private static final Logger LOG = Logger.getInstance(EditorUtil.class);

  private EditorUtil() {
  }

  /**
   * @return true if the editor is in fact an ordinary file editor;
   * false if the editor is part of EditorTextField, CommitMessage and etc.
   */
  public static boolean isRealFileEditor(@NotNull Editor editor) {
    return TextEditorProvider.getInstance().getTextEditor(editor) instanceof TextEditorImpl;
  }

  public static int getLastVisualLineColumnNumber(@NotNull Editor editor, final int line) {
    Document document = editor.getDocument();
    int lastLine = document.getLineCount() - 1;
    if (lastLine < 0) {
      return 0;
    }

    // Filter all lines that are not shown because of collapsed folding region.
    VisualPosition visStart = new VisualPosition(line, 0);
    LogicalPosition logStart = editor.visualToLogicalPosition(visStart);
    int lastLogLine = logStart.line;
    while (lastLogLine < document.getLineCount() - 1) {
      logStart = new LogicalPosition(logStart.line + 1, logStart.column);
      VisualPosition tryVisible = editor.logicalToVisualPosition(logStart);
      if (tryVisible.line != visStart.line) break;
      lastLogLine = logStart.line;
    }

    int resultLogLine = Math.min(lastLogLine, lastLine);
    VisualPosition resVisStart = editor.offsetToVisualPosition(document.getLineStartOffset(resultLogLine));
    VisualPosition resVisEnd = editor.offsetToVisualPosition(document.getLineEndOffset(resultLogLine));

    // Target logical line is not soft wrap affected.
    if (resVisStart.line == resVisEnd.line) {
      return resVisEnd.column;
    }

    int visualLinesToSkip = line - resVisStart.line;
    List<? extends SoftWrap> softWraps = editor.getSoftWrapModel().getSoftWrapsForLine(resultLogLine);
    for (int i = 0; i < softWraps.size(); i++) {
      SoftWrap softWrap = softWraps.get(i);
      CharSequence text = document.getCharsSequence();
      if (visualLinesToSkip <= 0) {
        VisualPosition visual = editor.offsetToVisualPosition(softWrap.getStart() - 1);
        int result = visual.column;
        int x = editor.visualPositionToXY(visual).x;
        // We need to add width of the next symbol because current result column points to the last symbol before the soft wrap.
        return  result + textWidthInColumns(editor, text, softWrap.getStart() - 1, softWrap.getStart(), x);
      }

      int softWrapLineFeeds = StringUtil.countNewLines(softWrap.getText());
      if (softWrapLineFeeds < visualLinesToSkip) {
        visualLinesToSkip -= softWrapLineFeeds;
        continue;
      }

      // Target visual column is located on the last visual line of the current soft wrap.
      if (softWrapLineFeeds == visualLinesToSkip) {
        if (i >= softWraps.size() - 1) {
          return resVisEnd.column;
        }
        // We need to find visual column for line feed of the next soft wrap.
        SoftWrap nextSoftWrap = softWraps.get(i + 1);
        VisualPosition visual = editor.offsetToVisualPosition(nextSoftWrap.getStart() - 1);
        int result = visual.column;
        int x = editor.visualPositionToXY(visual).x;

        // We need to add symbol width because current column points to the last symbol before the next soft wrap;
        result += textWidthInColumns(editor, text, nextSoftWrap.getStart() - 1, nextSoftWrap.getStart(), x);

        int lineFeedIndex = StringUtil.indexOf(nextSoftWrap.getText(), '\n');
        result += textWidthInColumns(editor, nextSoftWrap.getText(), 0, lineFeedIndex, 0);
        return result;
      }

      // Target visual column is the one before line feed introduced by the current soft wrap.
      int softWrapStartOffset = 0;
      int softWrapEndOffset = 0;
      int softWrapTextLength = softWrap.getText().length();
      while (visualLinesToSkip-- > 0) {
        softWrapStartOffset = softWrapEndOffset + 1;
        if (softWrapStartOffset >= softWrapTextLength) {
          assert false;
          return resVisEnd.column;
        }
        softWrapEndOffset = StringUtil.indexOf(softWrap.getText(), '\n', softWrapStartOffset, softWrapTextLength);
        if (softWrapEndOffset < 0) {
          assert false;
          return resVisEnd.column;
        }
      }
      VisualPosition visual = editor.offsetToVisualPosition(softWrap.getStart() - 1);
      int result = visual.column; // Column of the symbol just before the soft wrap
      int x = editor.visualPositionToXY(visual).x;

      // Target visual column is located on the last visual line of the current soft wrap.
      result += textWidthInColumns(editor, text, softWrap.getStart() - 1, softWrap.getStart(), x);
      result += calcColumnNumber(editor, softWrap.getText(), softWrapStartOffset, softWrapEndOffset);
      return result;
    }

    CharSequence editorInfo;
    if (editor instanceof EditorImpl) {
      editorInfo = ((EditorImpl)editor).dumpState();
    }
    else {
      editorInfo = "editor's class: " + editor.getClass()
                   + ", all soft wraps: " + editor.getSoftWrapModel().getSoftWrapsForRange(0, document.getTextLength())
                   + ", fold regions: " + Arrays.toString(editor.getFoldingModel().getAllFoldRegions());
    }
    LogMessageEx.error(LOG, "Can't calculate last visual column", String.format(
      "Target visual line: %d, mapped logical line: %d, visual lines range for the mapped logical line: [%s]-[%s], soft wraps for "
      + "the target logical line: %s. Editor info: %s",
      line, resultLogLine, resVisStart, resVisEnd, softWraps, editorInfo
    ));

    return resVisEnd.column;
  }

  public static int getVisualLineEndOffset(@NotNull Editor editor, int line) {
    VisualPosition endLineVisualPosition = new VisualPosition(line, getLastVisualLineColumnNumber(editor, line));
    LogicalPosition endLineLogicalPosition = editor.visualToLogicalPosition(endLineVisualPosition);
    return editor.logicalPositionToOffset(endLineLogicalPosition);
  }

  public static float calcVerticalScrollProportion(@NotNull Editor editor) {
    Rectangle viewArea = editor.getScrollingModel().getVisibleAreaOnScrollingFinished();
    if (viewArea.height == 0) {
      return 0;
    }
    LogicalPosition pos = editor.getCaretModel().getLogicalPosition();
    Point location = editor.logicalPositionToXY(pos);
    return (location.y - viewArea.y) / (float) viewArea.height;
  }

  public static void setVerticalScrollProportion(@NotNull Editor editor, float proportion) {
    Rectangle viewArea = editor.getScrollingModel().getVisibleArea();
    LogicalPosition caretPosition = editor.getCaretModel().getLogicalPosition();
    Point caretLocation = editor.logicalPositionToXY(caretPosition);
    int yPos = caretLocation.y;
    yPos -= viewArea.height * proportion;
    editor.getScrollingModel().scrollVertically(yPos);
  }

  public static void fillVirtualSpaceUntilCaret(@NotNull Editor editor) {
    final LogicalPosition position = editor.getCaretModel().getLogicalPosition();
    fillVirtualSpaceUntil(editor, position.column, position.line);
  }

  public static void fillVirtualSpaceUntil(@NotNull final Editor editor, int columnNumber, int lineNumber) {
    final int offset = editor.logicalPositionToOffset(new LogicalPosition(lineNumber, columnNumber));
    final String filler = EditorModificationUtil.calcStringToFillVirtualSpace(editor);
    if (!filler.isEmpty()) {
      new WriteAction(){
        @Override
        protected void run(@NotNull Result result) throws Throwable {
          editor.getDocument().insertString(offset, filler);
          editor.getCaretModel().moveToOffset(offset + filler.length());
        }
      }.execute();
    }
  }

  /**
   * Allows to calculate offset of the given column assuming that it belongs to the given text line identified by the
   * given <code>[start; end)</code> intervals.
   *
   * @param editor        editor that is used for representing given text
   * @param text          target text
   * @param start         start offset of the logical line that holds target column (inclusive)
   * @param end           end offset of the logical line that holds target column (exclusive)
   * @param columnNumber  target column number
   * @param tabSize       number of desired visual columns to use for tabulation representation
   * @param debugBuffer   buffer to hold debug info during the processing (if any)
   * @return              given text offset that identifies the same position that is pointed by the given visual column
   *
   * @deprecated This function can give incorrect results when soft wraps are enabled in editor. It is also slow in case of
   * long document lines - {@link com.intellij.openapi.editor.Editor#logicalPositionToOffset(com.intellij.openapi.editor.LogicalPosition)}
   * should be faster when soft wraps are enabled. To be removed in IDEA 16.
   */
  @SuppressWarnings("UnusedDeclaration")
  public static int calcOffset(@NotNull EditorEx editor,
                               @NotNull CharSequence text,
                               int start,
                               int end,
                               int columnNumber,
                               int tabSize,
                               @Nullable StringBuilder debugBuffer) {
    assert start >= 0 : "start (" + start + ") must not be negative. end (" + end + ")";
    assert end >= start : "start (" + start + ") must not be greater than end (" + end + ")";
    if (debugBuffer != null) {
      debugBuffer.append(String.format("Starting calcOffset(). Start=%d, end=%d, column number=%d, tab size=%d%n",
                                       start, end, columnNumber, tabSize));
    }
    final int maxScanIndex = Math.min(start + columnNumber + 1, end);
    SoftWrapModel softWrapModel = editor.getSoftWrapModel();
    List<? extends SoftWrap> softWraps = softWrapModel.getSoftWrapsForRange(start, maxScanIndex);
    int startToUse = start;
    int x = editor.getDocument().getLineNumber(start) == 0 ? editor.getPrefixTextWidthInPixels() : 0;
    int[] currentColumn = {0};
    for (SoftWrap softWrap : softWraps) {
      // There is a possible case that target column points inside soft wrap-introduced virtual space.
      if (currentColumn[0] >= columnNumber) {
        return startToUse;
      }
      int result
        = calcSoftWrapUnawareOffset(editor, text, startToUse, softWrap.getEnd(), columnNumber, tabSize, x, currentColumn, debugBuffer);
      if (result >= 0) {
        return result;
      }

      startToUse = softWrap.getStart();
      x = softWrap.getIndentInPixels();
    }

    // There is a possible case that target column points inside soft wrap-introduced virtual space.
    if (currentColumn[0] >= columnNumber) {
      return startToUse;
    }

    int result = calcSoftWrapUnawareOffset(editor, text, startToUse, end, columnNumber, tabSize, x, currentColumn, debugBuffer);
    if (result >= 0) {
      return result;
    }

    // We assume that given column points to the virtual space after the line end if control flow reaches this place,
    // hence, just return end of line offset then.
    if (debugBuffer != null) {
      debugBuffer.append(String.format("Returning %d as no match has been found for the target column (%d) at the target range [%d;%d)",
                                       end, columnNumber, start, end));
    }
    return end;
  }

  /**
   * Tries to match given logical column to the document offset assuming that it's located at <code>[start; end)</code> region.
   *
   * @param editor          editor that is used to represent target document
   * @param text            target document text
   * @param start           start offset to check (inclusive)
   * @param end             end offset to check (exclusive)
   * @param columnNumber    target logical column number
   * @param tabSize         user-defined desired number of columns to use for tabulation symbol representation
   * @param x               <code>'x'</code> coordinate that corresponds to the given <code>'start'</code> offset
   * @param currentColumn   logical column that corresponds to the given <code>'start'</code> offset
   * @param debugBuffer     buffer to hold debug info during the processing (if any)
   * @return                target offset that belongs to the <code>[start; end)</code> range and points to the target logical
   *                        column if any; <code>-1</code> otherwise
   */
  public static int calcSoftWrapUnawareOffset(@NotNull Editor editor,
                                               @NotNull CharSequence text,
                                               int start,
                                               int end,
                                               int columnNumber,
                                               int tabSize,
                                               int x,
                                               @NotNull int[] currentColumn,
                                               @Nullable StringBuilder debugBuffer) {
    if (debugBuffer != null) {
      debugBuffer.append(String.format(
        "Starting calcSoftWrapUnawareOffset(). Target range: [%d; %d), target column number to map: %d, tab size: %d, "
        + "x: %d, current column: %d%n", start, end, columnNumber, tabSize, x, currentColumn[0]));
    }

    // The main problem in a calculation is that target text may contain tabulation symbols and every such symbol may take different
    // number of logical columns to represent. E.g. it takes two columns if tab size is four and current column is two; three columns
    // if tab size is four and current column is one etc. So, first of all we check if there are tabulation symbols at the target
    // text fragment.
    boolean useOptimization = true;
    boolean hasTabs;
    if (editor instanceof EditorImpl && !((EditorImpl)editor).hasTabs()) {
      hasTabs = false;
      useOptimization = true;
    }
    else {
      hasTabs = false;
      int scanEndOffset = Math.min(end, start + columnNumber - currentColumn[0] + 1);
      boolean hasNonTabs = false;
      for (int i = start; i < scanEndOffset; i++) {
        char c = text.charAt(i);
        if (debugBuffer != null) {
          debugBuffer.append(String.format("Found symbol '%c' at the offset %d%n", c, i));
        }
        if (c == '\t') {
          hasTabs = true;
          if (hasNonTabs) {
            useOptimization = false;
            break;
          }
        }
        else {
          hasNonTabs = true;
        }
      }
    }

    if (debugBuffer != null) {
      debugBuffer.append(String.format("Has tabs: %b, use optimisation: %b%n", hasTabs, useOptimization));
    }

    // Perform optimized processing if possible. 'Optimized' here means the processing when we exactly know how many logical
    // columns are occupied by tabulation symbols.
    if (useOptimization) {
      if (!hasTabs) {
        int result = start + columnNumber - currentColumn[0];
        if (result < end) {
          return result;
        }
        else {
          currentColumn[0] += end - start;
          if (debugBuffer != null) {
            debugBuffer.append(String.format("Incrementing 'current column' by %d (new value is %d)%n", end - start, currentColumn[0]));
          }
          return -1;
        }
      }

      // This variable holds number of 'virtual' tab-introduced columns, e.g. there is a possible case that particular tab owns
      // three columns, hence, it increases 'shift' by two (3 - 1).
      int shift = 0;
      int offset = start;
      int prevX = x;
      if (debugBuffer != null) {
        debugBuffer.append("Processing a string that contains only tabs\n");
      }
      for (; offset < end && offset + shift + currentColumn[0] < start + columnNumber; offset++) {
        final char c = text.charAt(offset);
        if (c == '\t') {
          int nextX = nextTabStop(prevX, editor, tabSize);
          final int columnsShift = columnsNumber(nextX - prevX, getSpaceWidth(Font.PLAIN, editor)) - 1;
          if (debugBuffer != null) {
            debugBuffer.append(String.format(
              "Processing tabulation symbol at the offset %d. Current X: %d, new X: %d, current columns shift: %d, new column shift: %d%n",
              offset, prevX, nextX, shift, shift + columnsShift
            ));
          }
          shift += columnsShift;
          prevX = nextX;
        }
      }
      int diff = start + columnNumber - offset - shift - currentColumn[0];
      if (debugBuffer != null) debugBuffer.append(String.format("Resulting diff: %d%n", diff));
      if (diff < 0) {
        return offset - 1;
      }
      else if (diff == 0) {
        return offset;
      }
      else {
        final int inc = offset - start + shift;
        if (debugBuffer != null) {
          debugBuffer.append(String.format("Incrementing 'current column' by %d (new value is %d)%n", inc, currentColumn[0] + inc));
        }
        currentColumn[0] += inc;
        return -1;
      }
    }

    // It means that there are tabulation symbols that can't be explicitly mapped to the occupied logical columns number,
    // hence, we need to perform special calculations to get know that.
    EditorEx editorImpl = (EditorEx)editor;
    int offset = start;
    IterationState state = new IterationState(editorImpl, start, end, false);
    int fontType = state.getMergedAttributes().getFontType();
    int column = currentColumn[0];
    int plainSpaceSize = getSpaceWidth(Font.PLAIN, editorImpl);
    for (; column < columnNumber && offset < end; offset++) {
      if (offset >= state.getEndOffset()) {
        state.advance();
        fontType = state.getMergedAttributes().getFontType();
      }

      char c = text.charAt(offset);
      if (c == '\t') {
        final int newX = nextTabStop(x, editorImpl);
        final int columns = columnsNumber(newX - x, plainSpaceSize);
        if (debugBuffer != null) {
          debugBuffer.append(String.format(
            "Processing tabulation at the offset %d. Current X: %d, new X: %d, current column: %d, new column: %d%n",
            offset, x, newX, column, column + columns
          ));
        }
        x = newX;
        column += columns;
      }
      else {
        final int width = charWidth(c, fontType, editorImpl);
        if (debugBuffer != null) {
          debugBuffer.append(String.format(
            "Processing symbol '%c' at the offset %d. Current X: %d, new X: %d%n", c, offset, x, x + width
          ));
        }
        x += width;
        column++;
      }
    }

    if (column == columnNumber) {
      return offset;
    }
    if (column > columnNumber && offset > 0 && text.charAt(offset - 1) == '\t') {
      return offset - 1;
    }
    currentColumn[0] = column;
    return -1;
  }

  private static int getTabLength(int colNumber, int tabSize) {
    if (tabSize <= 0) {
      tabSize = 1;
    }
    return tabSize - colNumber % tabSize;
  }

  public static int calcColumnNumber(@NotNull Editor editor, @NotNull CharSequence text, int start, int offset) {
    return calcColumnNumber(editor, text, start, offset, getTabSize(editor));
  }

  public static int calcColumnNumber(@Nullable Editor editor, @NotNull CharSequence text, final int start, final int offset, final int tabSize) {
    boolean useOptimization = true;
    if (editor != null) {
      SoftWrap softWrap = editor.getSoftWrapModel().getSoftWrap(start);
      useOptimization = softWrap == null;
    }
    boolean hasTabs = true;
    if (useOptimization) {
      if (editor instanceof EditorImpl && !((EditorImpl)editor).hasTabs()) {
        hasTabs = false;
      }
      else {
        boolean hasNonTabs = false;
        for (int i = start; i < offset; i++) {
          if (text.charAt(i) == '\t') {
            if (hasNonTabs) {
              useOptimization = false;
              break;
            }
          }
          else {
            hasNonTabs = true;
          }
        }
      }
    }

    if (editor == null || useOptimization) {
      Document document = editor == null ? null : editor.getDocument();
      if (document != null && start < offset-1 && document.getLineNumber(start) != document.getLineNumber(offset-1)) {
        String editorInfo = editor instanceof EditorImpl ? ". Editor info: " + ((EditorImpl)editor).dumpState() : "";
        String documentInfo;
        if (text instanceof Dumpable) {
          documentInfo = ((Dumpable)text).dumpState();
        }
        else {
          documentInfo = "Text holder class: " + text.getClass();
        }
        LogMessageEx.error(
          LOG, "detected incorrect offset -> column number calculation",
          "start: " + start + ", given offset: " + offset+", given tab size: " + tabSize + ". "+documentInfo+ editorInfo);
      }
      int shift = 0;
      if (hasTabs) {
        for (int i = start; i < offset; i++) {
          char c = text.charAt(i);
          if (c == '\t') {
            shift += getTabLength(i + shift - start, tabSize) - 1;
          }
        }
      }
      return offset - start + shift;
    }

    EditorEx editorImpl = (EditorEx) editor;
    return editorImpl.calcColumnNumber(text, start, offset, tabSize);
  }

  public static void setHandCursor(@NotNull Editor view) {
    Cursor c = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
    // XXX: Workaround, simply view.getContentComponent().setCursor(c) doesn't work
    if (view.getContentComponent().getCursor() != c) {
      view.getContentComponent().setCursor(c);
    }
  }

  public static FontInfo fontForChar(final char c, @JdkConstants.FontStyle int style, @NotNull Editor editor) {
    EditorColorsScheme colorsScheme = editor.getColorsScheme();
    return ComplementaryFontsRegistry.getFontAbleToDisplay(c, style, colorsScheme.getFontPreferences());
  }

  public static int charWidth(char c, @JdkConstants.FontStyle int fontType, @NotNull Editor editor) {
    return fontForChar(c, fontType, editor).charWidth(c);
  }

  public static int getSpaceWidth(@JdkConstants.FontStyle int fontType, @NotNull Editor editor) {
    int width = charWidth(' ', fontType, editor);
    return width > 0 ? width : 1;
  }

  public static int getTabSize(@NotNull Editor editor) {
    return editor.getSettings().getTabSize(editor.getProject());
  }

  public static int nextTabStop(int x, @NotNull Editor editor) {
    int tabSize = getTabSize(editor);
    if (tabSize <= 0) {
      tabSize = 1;
    }
    return nextTabStop(x, editor, tabSize);
  }

  public static int nextTabStop(int x, @NotNull Editor editor, int tabSize) {
    return nextTabStop(x, getSpaceWidth(Font.PLAIN, editor), tabSize);
  }

  public static int nextTabStop(int x, int plainSpaceWidth, int tabSize) {
    if (tabSize <= 0) {
      return x + plainSpaceWidth;
    }
    tabSize *= plainSpaceWidth;

    int nTabs = x / tabSize;
    return (nTabs + 1) * tabSize;
  }

  public static int textWidthInColumns(@NotNull Editor editor, @NotNull CharSequence text, int start, int end, int x) {
    int startToUse = start;
    int lastTabSymbolIndex = -1;

    // Skip all lines except the last.
    loop:
    for (int i = end - 1; i >= start; i--) {
      switch (text.charAt(i)) {
        case '\n': startToUse = i + 1; break loop;
        case '\t': if (lastTabSymbolIndex < 0) lastTabSymbolIndex = i;
      }
    }

    // Tabulation is assumed to be the only symbol which representation may take various number of visual columns, hence,
    // we return eagerly if no such symbol is found.
    if (lastTabSymbolIndex < 0) {
      return end - startToUse;
    }

    int result = 0;
    int spaceSize = getSpaceWidth(Font.PLAIN, editor);

    // Calculate number of columns up to the latest tabulation symbol.
    for (int i = startToUse; i <= lastTabSymbolIndex; i++) {
      SoftWrap softWrap = editor.getSoftWrapModel().getSoftWrap(i);
      if (softWrap != null) {
        x = softWrap.getIndentInPixels();
      }
      char c = text.charAt(i);
      int prevX = x;
      switch (c) {
        case '\t':
          x = nextTabStop(x, editor);
          result += columnsNumber(x - prevX, spaceSize);
          break;
        case '\n': x = result = 0; break;
        default: x += charWidth(c, Font.PLAIN, editor); result++;
      }
    }

    // Add remaining tabulation-free columns.
    result += end - lastTabSymbolIndex - 1;
    return result;
  }

  /**
   * Allows to answer how many columns are necessary for representation of the given char on a screen.
   *
   * @param c           target char
   * @param x           <code>'x'</code> coordinate of the line where given char is represented that indicates char end location
   * @param prevX       <code>'x'</code> coordinate of the line where given char is represented that indicates char start location
   * @param plainSpaceSize   <code>'space'</code> symbol width (in plain font style)
   * @return            number of columns necessary for representation of the given char on a screen.
   */
  public static int columnsNumber(char c, int x, int prevX, int plainSpaceSize) {
    if (c != '\t') {
      return 1;
    }
    int result = (x - prevX) / plainSpaceSize;
    if ((x - prevX) % plainSpaceSize > 0) {
      result++;
    }
    return result;
  }

  /**
   * Allows to answer how many visual columns are occupied by the given width.
   *
   * @param width       target width
   * @param plainSpaceSize   width of the single space symbol within the target editor (in plain font style)
   * @return            number of visual columns are occupied by the given width
   */
  public static int columnsNumber(int width, int plainSpaceSize) {
    int result = width / plainSpaceSize;
    if (width % plainSpaceSize > 0) {
      result++;
    }
    return result;
  }

  /**
   * Allows to answer what width in pixels is required to draw fragment of the given char array from <code>[start; end)</code> interval
   * at the given editor.
   * <p/>
   * Tabulation symbols is processed specially, i.e. it's ta
   * <p/>
   * <b>Note:</b> it's assumed that target text fragment remains to the single line, i.e. line feed symbols within it are not
   * treated specially.
   *
   * @param editor    editor that will be used for target text representation
   * @param text      target text holder
   * @param start     offset within the given char array that points to target text start (inclusive)
   * @param end       offset within the given char array that points to target text end (exclusive)
   * @param fontType  font type to use for target text representation
   * @param x         <code>'x'</code> coordinate that should be used as a starting point for target text representation.
   *                  It's necessity is implied by the fact that IDEA editor may represent tabulation symbols in any range
   *                  from <code>[1; tab size]</code> (check {@link #nextTabStop(int, Editor)} for more details)
   * @return          width in pixels required for target text representation
   */
  public static int textWidth(@NotNull Editor editor, @NotNull CharSequence text, int start, int end, @JdkConstants.FontStyle int fontType, int x) {
    int result = 0;
    for (int i = start; i < end; i++) {
      char c = text.charAt(i);
      if (c != '\t') {
        FontInfo font = fontForChar(c, fontType, editor);
        result += font.charWidth(c);
        continue;
      }

      result += nextTabStop(x + result, editor) - result - x;
    }
    return result;
  }

  /**
   * Delegates to the {@link #calcSurroundingRange(Editor, VisualPosition, VisualPosition)} with the
   * {@link CaretModel#getVisualPosition() caret visual position} as an argument.
   *
   * @param editor  target editor
   * @return        surrounding logical positions
   * @see #calcSurroundingRange(Editor, VisualPosition, VisualPosition)
   */
  public static Pair<LogicalPosition, LogicalPosition> calcCaretLineRange(@NotNull Editor editor) {
    return calcSurroundingRange(editor, editor.getCaretModel().getVisualPosition(), editor.getCaretModel().getVisualPosition());
  }

  /**
   * Calculates logical positions that surround given visual positions and conform to the following criteria:
   * <pre>
   * <ul>
   *   <li>located at the start or the end of the visual line;</li>
   *   <li>doesn't have soft wrap at the target offset;</li>
   * </ul>
   * </pre>
   * Example:
   * <pre>
   *   first line [soft-wrap] some [start-position] text [end-position] [fold-start] fold line 1
   *   fold line 2
   *   fold line 3[fold-end] [soft-wrap] end text
   * </pre>
   * The very first and the last positions will be returned here.
   *
   * @param editor    target editor to use
   * @param start     target start coordinate
   * @param end       target end coordinate
   * @return          pair of the closest surrounding non-soft-wrapped logical positions for the visual line start and end
   *
   * @see #getNotFoldedLineStartOffset(com.intellij.openapi.editor.Editor, int)
   * @see #getNotFoldedLineEndOffset(com.intellij.openapi.editor.Editor, int)
   */
  @SuppressWarnings("AssignmentToForLoopParameter")
  public static Pair<LogicalPosition, LogicalPosition> calcSurroundingRange(@NotNull Editor editor,
                                                                            @NotNull VisualPosition start,
                                                                            @NotNull VisualPosition end) {
    final Document document = editor.getDocument();
    final FoldingModel foldingModel = editor.getFoldingModel();

    LogicalPosition first = editor.visualToLogicalPosition(new VisualPosition(start.line, 0));
    for (
      int line = first.line, offset = document.getLineStartOffset(line);
      offset >= 0;
      offset = document.getLineStartOffset(line)) {
      final FoldRegion foldRegion = foldingModel.getCollapsedRegionAtOffset(offset);
      if (foldRegion == null) {
        first = new LogicalPosition(line, 0);
        break;
      }
      final int foldEndLine = document.getLineNumber(foldRegion.getStartOffset());
      if (foldEndLine <= line) {
        first = new LogicalPosition(line, 0);
        break;
      }
      line = foldEndLine;
    }


    LogicalPosition second = editor.visualToLogicalPosition(new VisualPosition(end.line, 0));
    for (
      int line = second.line, offset = document.getLineEndOffset(line);
      offset <= document.getTextLength();
      offset = document.getLineEndOffset(line)) {
      final FoldRegion foldRegion = foldingModel.getCollapsedRegionAtOffset(offset);
      if (foldRegion == null) {
        second = new LogicalPosition(line + 1, 0);
        break;
      }
      final int foldEndLine = document.getLineNumber(foldRegion.getEndOffset());
      if (foldEndLine <= line) {
        second = new LogicalPosition(line + 1, 0);
        break;
      }
      line = foldEndLine;
    }

    if (second.line >= document.getLineCount()) {
      second = editor.offsetToLogicalPosition(document.getTextLength());
    }
    return Pair.create(first, second);
  }

  /**
   * Finds the start offset of visual line at which given offset is located, not taking soft wraps into account.
   */
  public static int getNotFoldedLineStartOffset(@NotNull Editor editor, int offset) {
    while(true) {
      offset = getLineStartOffset(offset, editor.getDocument());
      FoldRegion foldRegion = editor.getFoldingModel().getCollapsedRegionAtOffset(offset - 1);
      if (foldRegion == null || foldRegion.getStartOffset() >= offset) {
        break;
      }
      offset = foldRegion.getStartOffset();
    }
    return offset;
  }

  /**
   * Finds the end offset of visual line at which given offset is located, not taking soft wraps into account.
   */
  public static int getNotFoldedLineEndOffset(@NotNull Editor editor, int offset) {
    while(true) {
      offset = getLineEndOffset(offset, editor.getDocument());
      FoldRegion foldRegion = editor.getFoldingModel().getCollapsedRegionAtOffset(offset);
      if (foldRegion == null || foldRegion.getEndOffset() <= offset) {
        break;
      }
      offset = foldRegion.getEndOffset();
    }
    return offset;
  }

  private static int getLineStartOffset(int offset, Document document) {
    if (offset > document.getTextLength()) {
      return offset;
    }
    int lineNumber = document.getLineNumber(offset);
    return document.getLineStartOffset(lineNumber);
  }

  private static int getLineEndOffset(int offset, Document document) {
    if (offset >= document.getTextLength()) {
      return offset;
    }
    int lineNumber = document.getLineNumber(offset);
    return document.getLineEndOffset(lineNumber);
  }

  public static void scrollToTheEnd(@NotNull Editor editor) {
    editor.getSelectionModel().removeSelection();
    int lastLine = Math.max(0, editor.getDocument().getLineCount() - 1);
    if (editor.getCaretModel().getLogicalPosition().line == lastLine) {
      editor.getCaretModel().moveToOffset(editor.getDocument().getTextLength());
    } else {
      editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(lastLine, 0));
    }
    editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
  }

  public static boolean isChangeFontSize(@NotNull MouseWheelEvent e) {
    return SystemInfo.isMac
           ? !e.isControlDown() && e.isMetaDown() && !e.isAltDown() && !e.isShiftDown()
           : e.isControlDown() && !e.isMetaDown() && !e.isAltDown() && !e.isShiftDown();
  }

  public static boolean inVirtualSpace(@NotNull Editor editor, @NotNull LogicalPosition logicalPosition) {
    return !editor.offsetToLogicalPosition(editor.logicalPositionToOffset(logicalPosition)).equals(logicalPosition);
  }

  public static void reinitSettings() {
    EditorFactory.getInstance().refreshAllEditors();
  }

  @NotNull
  public static TextRange getSelectionInAnyMode(Editor editor) {
    SelectionModel selection = editor.getSelectionModel();
    int[] starts = selection.getBlockSelectionStarts();
    int[] ends = selection.getBlockSelectionEnds();
    int start = starts.length > 0 ? starts[0] : selection.getSelectionStart();
    int end = ends.length > 0 ? ends[ends.length - 1] : selection.getSelectionEnd();
    return TextRange.create(start, end);
  }

  public static int yPositionToLogicalLine(@NotNull Editor editor, @NotNull MouseEvent event) {
    return yPositionToLogicalLine(editor, event.getY());
  }

  public static int yPositionToLogicalLine(@NotNull Editor editor, @NotNull Point point) {
    return yPositionToLogicalLine(editor, point.y);
  }

  public static int yPositionToLogicalLine(@NotNull Editor editor, int y) {
    int line = y / editor.getLineHeight();
    return line > 0 ? editor.visualToLogicalPosition(new VisualPosition(line, 0)).line : 0;
  }

  public static boolean isAtLineEnd(@NotNull Editor editor, int offset) {
    Document document = editor.getDocument();
    if (offset < 0 || offset > document.getTextLength()) {
      return false;
    }
    int line = document.getLineNumber(offset);
    return offset == document.getLineEndOffset(line);
  }
}


