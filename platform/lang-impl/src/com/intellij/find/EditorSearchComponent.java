/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

/*
 * @author max, zajac
 */
package com.intellij.find;

import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.find.editorHeaderActions.*;
import com.intellij.find.impl.FindManagerImpl;
import com.intellij.find.impl.RegexpFieldController;
import com.intellij.find.impl.livePreview.LiveOccurrence;
import com.intellij.find.impl.livePreview.LivePreview;
import com.intellij.find.impl.livePreview.LivePreviewControllerBase;
import com.intellij.find.impl.livePreview.SearchResults;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.EditorComboBox;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.LightColors;
import com.intellij.ui.NonFocusableCheckBox;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.components.labels.LinkListener;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.HashMap;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

public class EditorSearchComponent extends JPanel implements DataProvider, SelectionListener, SearchResults.SearchResultsListener,
                                                             RegexpFieldController.EditorDocumentListener,
                                                             LivePreviewControllerBase.ReplaceListener {
  private static final int MATCHES_LIMIT = 10000;
  private final JLabel myMatchInfoLabel;
  private final LinkLabel myClickToHighlightLabel;
  private final Project myProject;
  private RegexpFieldController mySearchRegexpFieldController;
  private RegexpFieldController myReplaceRegexpFieldController;
  private boolean mySearchFieldConfigured = false;

  public Editor getEditor() {
    return myEditor;
  }

  private final Editor myEditor;
  private DefaultActionGroup myActionsGroup;

  private Map<EditorComboBox, DocumentAdapter> myDocumentListeners = new HashMap<EditorComboBox,DocumentAdapter>();

  public EditorComboBox getSearchField() {
    return mySearchField;
  }

  public EditorComboBox getReplaceField() {
    return myReplaceField;
  }

  private final EditorComboBox mySearchField;
  private EditorComboBox myReplaceField;
  private final Color myDefaultBackground;

  private JButton myReplaceButton;
  private JButton myReplaceAllButton;
  private JButton myExcludeButton;

  private final Color GRADIENT_C1;

  private final Color GRADIENT_C2;
  private static final Color BORDER_COLOR = new Color(0x87, 0x87, 0x87);
  public static final Color COMPLETION_BACKGROUND_COLOR = new Color(235, 244, 254);
  private static final Color FOCUS_CATCHER_COLOR = new Color(0x9999ff);
  private final JComponent myToolbarComponent;
  private DocumentAdapter myDocumentListener;
  private final JCheckBox myCbRegexp;

  private MyLivePreviewController myLivePreviewController;
  private LivePreview myLivePreview;


  private boolean myListeningSelection = false;
  private SearchResults mySearchResults;

  private final FindModel myFindModel;
  private JCheckBox myCbMatchCase;
  private JPanel myReplacementPane;

  public JComponent getToolbarComponent() {
    return myToolbarComponent;
  }

  @Override
  public void documentWillBeReplaced(RegexpFieldController controller) {
    DocumentListener listener = myDocumentListeners.get(controller.getTextField());
    if (listener != null) {
      controller.getTextField().removeDocumentListener(listener);
    }
  }

  @Override
  public void documentWasReplaced(RegexpFieldController controller) {
    DocumentListener listener = myDocumentListeners.get(controller.getTextField());
    if (listener != null) {
      controller.getTextField().addDocumentListener(listener);
    }
  }

  @Override
  public void replacePerformed(LiveOccurrence occurrence, String replacement, Editor editor) {  }

  @Override
  public void replaceAllPerformed(Editor e) {  }

  @Override
  public void replaceDenied() {
    updateReplaceButton();
  }

  @Override
  public void replaceAllowed() {
    updateReplaceButton();
  }

  private void updateReplaceButton() {
    if (myReplaceButton != null) {
      myReplaceButton.setEnabled(mySearchResults != null && mySearchResults.getCursor() != null &&
                                 !myLivePreviewController.isReplaceDenied());
    }
  }

  private static FindModel createDefaultFindModel(Project p, Editor e) {
    FindModel findModel = new FindModel();
    findModel.copyFrom(FindManager.getInstance(p).getFindInFileModel());
    if (e.getSelectionModel().hasSelection()) {
      String selectedText = e.getSelectionModel().getSelectedText();
      if (selectedText != null) {
        findModel.setStringToFind(selectedText);
      }
    }
    findModel.setPromptOnReplace(false);
    return findModel;
  }

  public EditorSearchComponent(Editor editor, Project project) {
    this(editor, project, createDefaultFindModel(project, editor));
  }

  @Nullable
  public Object getData(@NonNls final String dataId) {
    if (PlatformDataKeys.EDITOR_EVEN_IF_INACTIVE.is(dataId)) {
      return myEditor;
    }
    return null;
  }

  @Override
  public void searchResultsUpdated(SearchResults sr) {
    int count = sr.getActualFound();
    if (mySearchField.getText().isEmpty()) {
      updateUIWithEmptyResults();
    } else {
      if (count <= mySearchResults.getMatchesLimit()) {
        myClickToHighlightLabel.setVisible(false);

        if (count > 0) {
          setRegularBackground();
          if (count > 1) {
            myMatchInfoLabel.setText(count + " matches");
          }
          else {
            myMatchInfoLabel.setText("1 match");
          }
        }
        else {
          setNotFoundBackground();
          myMatchInfoLabel.setText("No matches");
        }
      }
      else {
        setRegularBackground();
        myMatchInfoLabel.setText("More than " + mySearchResults.getMatchesLimit() + " matches");
        myClickToHighlightLabel.setVisible(true);
        boldMatchInfo();
      }
    }

    updateExcludeStatus();
  }

  @Override
  public void cursorMoved(boolean toChangeSelection) {
    updateExcludeStatus();
  }

  @Override
  public void editorChanged(SearchResults sr, Editor oldEditor) {  }

  public EditorSearchComponent(final Editor editor, final Project project, FindModel findModel) {
    super(new BorderLayout(0, 0));
    myFindModel = findModel;

    GRADIENT_C1 = getBackground();
    GRADIENT_C2 = new Color(Math.max(0, GRADIENT_C1.getRed() - 0x18), Math.max(0, GRADIENT_C1.getGreen() - 0x18), Math.max(0, GRADIENT_C1.getBlue() - 0x18));

    myProject = project;
    myEditor = editor;

    final JPanel leadPanel = createLeadPane();
    add(leadPanel, BorderLayout.WEST);
    mySearchField = createTextField(leadPanel);
    updateHistory(mySearchField);

    mySearchField.putClientProperty("AuxEditorComponent", Boolean.TRUE);

    myDefaultBackground = new JTextField().getBackground();

    myActionsGroup = new DefaultActionGroup("search bar", false);
    myActionsGroup.add(new PrevOccurrenceAction(this, mySearchField));
    myActionsGroup.add(new NextOccurrenceAction(this, mySearchField));
    myActionsGroup.add(new FindAllAction(this));

    myActionsGroup.addAction(new ToggleWholeWordsOnlyAction(this)).setAsSecondary(true);
    if (FindManagerImpl.ourHasSearchInCommentsAndLiterals) {
      myActionsGroup.addAction(new ToggleInCommentsAction(this)).setAsSecondary(true);
      myActionsGroup.addAction(new ToggleInLiteralsOnlyAction(this)).setAsSecondary(true);
    }
    myActionsGroup.addAction(new TogglePreserveCaseAction(this)).setAsSecondary(true);
    myActionsGroup.addAction(new ToggleSelectionOnlyAction(this)).setAsSecondary(true);

    final ActionToolbar tb = ActionManager.getInstance().createActionToolbar("SearchBar", myActionsGroup, true);
    tb.setLayoutPolicy(ActionToolbar.AUTO_LAYOUT_POLICY);
    myToolbarComponent = tb.getComponent();
    myToolbarComponent.setBorder(null);
    myToolbarComponent.setOpaque(false);
    leadPanel.add(myToolbarComponent);

    myCbMatchCase = new NonFocusableCheckBox("Case sensitive");
    myCbRegexp = new NonFocusableCheckBox("Regex");

    leadPanel.add(myCbMatchCase);
    leadPanel.add(myCbRegexp);

    myFindModel.addObserver(new FindModel.FindModelObserver() {
      @Override
      public void findModelChanged(FindModel findModel) {
        String stringToFind = myFindModel.getStringToFind();
        if (!wholeWordsApplicable(stringToFind)) {
          myFindModel.setWholeWordsOnly(false);
        }
        updateUIWithFindModel();
        updateResults(true);
        syncFindModels(FindManager.getInstance(myProject).getFindInFileModel(), myFindModel);
      }
    });

    updateUIWithFindModel();

    myCbMatchCase.setMnemonic('C');
    myCbRegexp.setMnemonic('e');

    setSmallerFontAndOpaque(myCbMatchCase);
    setSmallerFontAndOpaque(myCbRegexp);


    myCbMatchCase.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final boolean b = myCbMatchCase.isSelected();
        FindSettings.getInstance().setLocalCaseSensitive(b);
        myFindModel.setCaseSensitive(b);
      }
    });

    myCbRegexp.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        final boolean b = myCbRegexp.isSelected();
        myFindModel.setRegularExpressions(b);
      }
    });

    JPanel tailPanel = new NonOpaquePanel(new BorderLayout(5, 0));
    JPanel tailContainer = new NonOpaquePanel(new BorderLayout(5, 0));
    tailContainer.add(tailPanel, BorderLayout.EAST);
    add(tailContainer, BorderLayout.CENTER);

    myMatchInfoLabel = new JLabel();
    setSmallerFontAndOpaque(myMatchInfoLabel);

    myClickToHighlightLabel = new LinkLabel("Click to highlight", null, new LinkListener() {
      @Override
      public void linkSelected(LinkLabel aSource, Object aLinkData) {
        setMatchesLimit(Integer.MAX_VALUE);
        updateResults(true);
      }
    });
    setSmallerFontAndOpaque(myClickToHighlightLabel);
    myClickToHighlightLabel.setVisible(false);

    JLabel closeLabel = new JLabel(" ", IconLoader.getIcon("/actions/cross.png"), SwingConstants.RIGHT);
    closeLabel.addMouseListener(new MouseAdapter() {
      public void mousePressed(final MouseEvent e) {
        close();
      }
    });

    closeLabel.setToolTipText("Close search bar (Escape)");

    JPanel labelsPanel = new NonOpaquePanel(new FlowLayout());

    labelsPanel.add(myMatchInfoLabel);
    labelsPanel.add(myClickToHighlightLabel);
    tailPanel.add(labelsPanel, BorderLayout.CENTER);
    tailPanel.add(closeLabel, BorderLayout.EAST);

    setSmallerFont(mySearchField);
    mySearchField.registerKeyboardAction(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        if ("".equals(mySearchField.getText())) {
          close();
        }
        else {
          requestFocus(myEditor.getContentComponent());
          addTextToRecents(EditorSearchComponent.this.mySearchField);
        }
      }
    }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, SystemInfo.isMac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK),
                                         JComponent.WHEN_FOCUSED);

    final String initialText = myFindModel.getStringToFind();

    ApplicationManager.getApplication().invokeLater(new Runnable() {
      public void run() {
        setInitialText(initialText);
      }
    });

    new VariantsCompletionAction(this, mySearchField); // It registers a shortcut set automatically on construction

    new SwitchToFind(this);
    new SwitchToReplace(this);
    mySearchRegexpFieldController = new RegexpFieldController(mySearchField, myFindModel, this);
  }

  private void setupSearchFieldListener() {
    DocumentAdapter searchFieldListener = new DocumentAdapter() {
      @Override
      public void documentChanged(DocumentEvent e) {
        setMatchesLimit(MATCHES_LIMIT);
        String text = mySearchField.getText();
        myFindModel.setStringToFind(text);
        if (!StringUtil.isEmpty(text)) {
          updateResults(true);
        }
        else {
          nothingToSearchFor();
        }
      }
    };
    mySearchField.addDocumentListener(searchFieldListener);
    myDocumentListeners.put(mySearchField, searchFieldListener);
  }

  public boolean isRegexp() {
    return myFindModel.isRegularExpressions();
  }

  public void setRegexp(boolean val) {
    myFindModel.setRegularExpressions(val);
  }

  public FindModel getFindModel() {
    return myFindModel;
  }

  private static void syncFindModels(FindModel to, FindModel from) {
    to.setCaseSensitive(from.isCaseSensitive());
    to.setWholeWordsOnly(from.isWholeWordsOnly());
    to.setRegularExpressions(from.isRegularExpressions());
    to.setInCommentsOnly(from.isInCommentsOnly());
    to.setInStringLiteralsOnly(from.isInStringLiteralsOnly());
  }

  private void updateFindModelWithUI() {
    myFindModel.setCaseSensitive(myCbMatchCase.isSelected());
    myFindModel.setRegularExpressions(myCbRegexp.isSelected());
    myFindModel.setFromCursor(false);
    myFindModel.setSearchHighlighters(true);

  }

  private void updateUIWithFindModel() {
    myCbMatchCase.setSelected(myFindModel.isCaseSensitive());
    myCbRegexp.setSelected(myFindModel.isRegularExpressions());

    String stringToFind = myFindModel.getStringToFind();

    if (!StringUtil.equals(stringToFind, mySearchField.getText())) {
      mySearchField.setText(stringToFind);
    }

    setTrackingSelection(!myFindModel.isGlobal());

    if (myFindModel.isReplaceState() && myReplacementPane == null) {
      configureReplacementPane();
    } else if (!myFindModel.isReplaceState() && myReplacementPane != null) {
      remove(myReplacementPane);
      myReplacementPane = null;
    }
    if (myFindModel.isReplaceState()) {
      String stringToReplace = myFindModel.getStringToReplace();
      if (!StringUtil.equals(stringToReplace, myReplaceField.getText())) {
        myReplaceField.setText(stringToReplace);
      }
      updateExcludeStatus();
    }
  }

  private void updateHistory(EditorComboBox textField) {
    FeatureUsageTracker.getInstance().triggerFeatureUsed("find.recent.search");
    FindSettings settings = FindSettings.getInstance();
    String[] recents = textField == mySearchField ?  settings.getRecentFindStrings() : settings.getRecentReplaceStrings();
    if (textField.getEditor().getEditorComponent() != null) {
      textField.setHistory(ArrayUtil.reverseArray(recents));
    }
  }

  private boolean wholeWordsApplicable(String stringToFind) {
    return !stringToFind.startsWith(" ") &&
           !stringToFind.startsWith("\t") &&
           !stringToFind.endsWith(" ") &&
           !stringToFind.endsWith("\t");
  }

  private static FindModel createFindModel(FindModel findInFileModel, boolean isReplace) {
    FindModel result = new FindModel();
    result.copyFrom(findInFileModel);
    if (isReplace) {
      result.setReplaceState(isReplace);
    }
    return result;
  }

  private void setMatchesLimit(int value) {
    if (mySearchResults != null) {
      mySearchResults.setMatchesLimit(value);
    }
  }

  private void configureReplacementPane() {
    myReplacementPane = createLeadPane();
    myReplaceField = createTextField(myReplacementPane);
    configureTextField(myReplaceField);
    updateHistory(myReplaceField);
    DocumentAdapter replaceFieldListener = new DocumentAdapter() {
      @Override
      public void documentChanged(DocumentEvent event) {
        setMatchesLimit(MATCHES_LIMIT);
        myFindModel.setStringToReplace(myReplaceField.getText());
      }
    };

    myReplaceField.addDocumentListener(replaceFieldListener);
    myDocumentListeners.put(myReplaceField, replaceFieldListener);

    new ReplaceOnEnterAction(this, myReplaceField);

    myReplaceField.setText(myFindModel.getStringToReplace());
    add(myReplacementPane, BorderLayout.SOUTH);

    myReplaceButton = new JButton("Replace");
    myReplaceButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        replaceCurrent();
      }
    });
    myReplaceButton.setMnemonic('p');

    myReplaceAllButton = new JButton("Replace all");
    myReplaceAllButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        myLivePreviewController.performReplaceAll();
      }
    });
    myReplaceAllButton.setMnemonic('a');

    myExcludeButton = new JButton("");

    myExcludeButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent actionEvent) {
        myLivePreviewController.exclude();
      }
    });
    myExcludeButton.setMnemonic('l');

    myReplacementPane.add(myReplaceButton);
    myReplacementPane.add(myReplaceAllButton);
    myReplacementPane.add(myExcludeButton);

    setSmallerFontAndOpaque(myReplaceButton);
    setSmallerFontAndOpaque(myReplaceAllButton);
    setSmallerFontAndOpaque(myExcludeButton);
    
    setSmallerFont(myReplaceField);
    myReplaceField.putClientProperty("AuxEditorComponent", Boolean.TRUE);
    new VariantsCompletionAction(this, myReplaceField);
    new NextOccurrenceAction(this, myReplaceField);
    new PrevOccurrenceAction(this, myReplaceField);
    myReplaceRegexpFieldController = new RegexpFieldController(myReplaceField, myFindModel, this);
  }

  public void replaceCurrent() {
    myLivePreviewController.performReplace();
  }

  private void updateExcludeStatus() {
    if (myExcludeButton != null && mySearchResults != null) {
      LiveOccurrence cursor = mySearchResults.getCursor();
      myExcludeButton.setText(cursor == null || !mySearchResults.isExcluded(cursor) ? "Exclude" : "Include");
      myReplaceAllButton.setEnabled(mySearchResults.hasMatches());
      myExcludeButton.setEnabled(cursor != null);
      updateReplaceButton();
    }
  }

  private void setTrackingSelection(boolean b) {
    if (b) {
      if (!myListeningSelection) {
        myEditor.getSelectionModel().addSelectionListener(this);
      }
    } else {
      if (myListeningSelection) {
        myEditor.getSelectionModel().removeSelectionListener(this);
      }
    }
    myListeningSelection = b;
  }

  private JPanel createLeadPane() {
    return new NonOpaquePanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
  }

  private EditorComboBox createTextField(JPanel leadPanel) {
    final EditorComboBox editorComboBox = new EditorComboBox("", myProject, PlainTextFileType.INSTANCE) {
      protected void paintBorder(final Graphics g) {
        super.paintBorder(g);

        if (!(UIUtil.isUnderAquaLookAndFeel() || UIUtil.isUnderQuaquaLookAndFeel()) && isFocusOwner()) {
          final Rectangle bounds = getBounds();
          g.setColor(FOCUS_CATCHER_COLOR);
          g.drawRect(0, 0, bounds.width - 1, bounds.height - 1);
        }
      }
    };
    JPanel searchFieldPane = new JPanel(new BorderLayout());
    setSmallerFontAndOpaque(searchFieldPane);
    Dimension dimension = new Dimension(200, 25);
    searchFieldPane.add(editorComboBox, BorderLayout.CENTER);
    searchFieldPane.setMinimumSize(dimension);
    searchFieldPane.setPreferredSize(dimension);
    leadPanel.add(searchFieldPane);

    editorComboBox.addFocusListener(new FocusListener() {
      public void focusGained(final FocusEvent e) {
        editorComboBox.repaint();
      }

      public void focusLost(final FocusEvent e) {
        editorComboBox.repaint();
      }
    });
    new CloseOnESCAction(this, editorComboBox);
    return editorComboBox;
  }

  private void configureTextField(final EditorComboBox searchField) {
    //updateHistory(searchField);
  }

  public void setInitialText(final String initialText) {
    final String text = initialText != null ? initialText : "";
    if (text.contains("\n")) {
      myFindModel.setRegularExpressions(true);
      setTextInField(StringUtil.escapeToRegexp(text));
    }
    else {
      setTextInField(text);
    }
    Component editorComponent = mySearchField.getEditor().getEditorComponent();
    if (editorComponent  instanceof EditorTextField) {
      ((EditorTextField)editorComponent).selectAll();
    }
  }

  private void requestFocus(Component c) {
    IdeFocusManager.getInstance(myProject).requestFocus(c, true);
  }

  public void searchBackward() {
    moveCursor(SearchResults.Direction.UP);

    addTextToRecents(mySearchField);
  }

  public void searchForward() {
    moveCursor(SearchResults.Direction.DOWN);
    addTextToRecents(mySearchField);
  }

  private void addTextToRecents(EditorComboBox textField) {
    final String text = textField.getText();
    if (text.length() > 0) {
      if (textField == mySearchField) {
        FindSettings.getInstance().addStringToFind(text);
      } else {
        FindSettings.getInstance().addStringToReplace(text);
      }
      //updateHistory(textField);  //todo[zajac]: update combobox model
    }
  }

  @Override
  public void selectionChanged(SelectionEvent e) {
    updateResults(true);
  }

  private void moveCursor(SearchResults.Direction direction) {
    myLivePreviewController.moveCursor(direction);
  }

  private static void setSmallerFontAndOpaque(final JComponent component) {
    setSmallerFont(component);
    component.setOpaque(false);
  }

  private static void setSmallerFont(final JComponent component) {
    if (SystemInfo.isMac) {
      Font f = component.getFont();
      component.setFont(f.deriveFont(f.getStyle(), f.getSize() - 2));
    }
  }

  public void requestFocus() {
    requestFocus(mySearchField);
  }

  public void close() {
    if (myEditor.getSelectionModel().hasSelection()) {
      myEditor.getCaretModel().moveToOffset(myEditor.getSelectionModel().getSelectionStart());
      myEditor.getSelectionModel().removeSelection();
    }
    IdeFocusManager.getInstance(myProject).requestFocus(myEditor.getContentComponent(), false);
    mySearchResults.dispose();
    myLivePreview.cleanUp();
    myEditor.setHeaderComponent(null);
  }

  @Override
  public void addNotify() {
    super.addNotify();
    updateHistory(mySearchField);
    if (myReplaceField != null) {
      updateHistory(myReplaceField);
    }
    myDocumentListener = new DocumentAdapter() {
      public void documentChanged(final DocumentEvent e) {
        updateResults(false);
      }
    };

    myEditor.getDocument().addDocumentListener(myDocumentListener);
    setupSearchFieldListener();

    mySearchResults = new SearchResults(myEditor);
    setMatchesLimit(MATCHES_LIMIT);

    myLivePreview = new LivePreview(mySearchResults);

    myLivePreviewController = new MyLivePreviewController();
    myLivePreviewController.setReplaceListener(this);
    mySearchResults.addListener(this);

    mySearchRegexpFieldController.updateRegexpState();
    if (myReplaceRegexpFieldController != null) {
      myReplaceRegexpFieldController.updateRegexpState();
    }
    
    myLivePreviewController.updateInBackground(myFindModel, false);
  }

  public void removeNotify() {
    super.removeNotify();

    if (myDocumentListener != null) {
      myEditor.getDocument().removeDocumentListener(myDocumentListener);
      myDocumentListener = null;
    }
    mySearchResults.dispose();
    myLivePreview.cleanUp();
    if (myListeningSelection) {
      myEditor.getSelectionModel().removeSelectionListener(this);
    }
    addTextToRecents(mySearchField);
    if (myReplaceField != null) {
      addTextToRecents(myReplaceField);
    }
  }

  private void updateResults(final boolean allowedToChangedEditorSelection) {
    myMatchInfoLabel.setFont(myMatchInfoLabel.getFont().deriveFont(Font.PLAIN));
    final String text = mySearchField.getText();
    if (text.length() == 0) {
      nothingToSearchFor();
    }
    else {

      if (myFindModel.isRegularExpressions()) {
        try {
          Pattern.compile(text);
        }
        catch (Exception e) {
          setNotFoundBackground();
          myMatchInfoLabel.setText("Incorrect regular expression");
          boldMatchInfo();
          myClickToHighlightLabel.setVisible(false);
          mySearchResults.clear();
          return;
        }
      }


      final FindManager findManager = FindManager.getInstance(myProject);
      if (allowedToChangedEditorSelection) {
        findManager.setFindWasPerformed();
        FindModel copy = new FindModel();
        copy.copyFrom(myFindModel);
        copy.setReplaceState(false);
        findManager.setFindNextModel(copy);
      }
      
      myLivePreviewController.updateInBackground(myFindModel, allowedToChangedEditorSelection);
    }
  }

  private void nothingToSearchFor() {
    updateUIWithEmptyResults();
    if (mySearchResults != null) {
      mySearchResults.clear();
    }
  }

  private void updateUIWithEmptyResults() {
    setRegularBackground();
    myMatchInfoLabel.setText("");
    myClickToHighlightLabel.setVisible(false);
  }

  private void boldMatchInfo() {
    myMatchInfoLabel.setFont(myMatchInfoLabel.getFont().deriveFont(Font.BOLD));
  }

  private void setRegularBackground() {
    Component editorComponent = mySearchField.getEditor().getEditorComponent();
    if (editorComponent != null) {
      editorComponent.setBackground(myDefaultBackground);
    }
  }

  private void setNotFoundBackground() {
    Component editorComponent = mySearchField.getEditor().getEditorComponent();
    if (editorComponent != null) {
      editorComponent.setBackground(LightColors.RED);
    }
  }

  public String getTextInField() {
    return mySearchField.getText();
  }

  public void setTextInField(final String text) {
    mySearchField.setText(text);
    myFindModel.setStringToFind(text);
  }

  public boolean hasMatches() {
    return myLivePreview != null && myLivePreview.hasMatches();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    final Graphics2D g2d = (Graphics2D) g;

    g2d.setPaint(new GradientPaint(0, 0, GRADIENT_C1, 0, getHeight(), GRADIENT_C2));
    g2d.fillRect(1, 1, getWidth(), getHeight() - 1);
    
    g.setColor(BORDER_COLOR);
    g2d.setPaint(null);
    g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
  }

  private class MyLivePreviewController extends LivePreviewControllerBase {
    public MyLivePreviewController() {
      super(EditorSearchComponent.this.mySearchResults, EditorSearchComponent.this.myLivePreview);
    }

    @Override
    public void getFocusBack() {
      if (myFindModel != null && myFindModel.isReplaceState()) {
        requestFocus(myReplaceField);
      } else {
        requestFocus(mySearchField);
      }
    }

    public void performReplace() {
      String replacement = getStringToReplace(myEditor, mySearchResults.getCursor());
      performReplace(mySearchResults.getCursor(), replacement, myEditor);
      getFocusBack();
      addTextToRecents(myReplaceField);
    }

    public void exclude() {
      mySearchResults.exclude(mySearchResults.getCursor());
    }

    public void performReplaceAll() {
      performReplaceAll(myEditor);
    }
  }
}
