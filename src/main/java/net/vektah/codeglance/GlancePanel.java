/*
 * Copyright © 2013, Adam Scarr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.vektah.codeglance;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.*;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import net.vektah.codeglance.render.Minimap;
import net.vektah.codeglance.render.RenderTask;
import net.vektah.codeglance.render.TaskRunner;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.lang.reflect.Array;

/**
 * This JPanel gets injected into editor windows and renders a image generated by GlanceFileRenderer
 */
public class GlancePanel extends JPanel implements VisibleAreaListener {
	public static final int MAX_WIDTH = 100;
	private final TaskRunner runner;
	private Editor editor;
	private Minimap[] minimaps = new Minimap[2];
	private Integer activeBuffer = -1;
	private Integer nextBuffer = 0;
	private JPanel container;
	private Logger logger = Logger.getInstance(getClass().getName());
	private Project project;
	private Boolean updatePending = false;
	private boolean dirty = false;

	public GlancePanel(Project project, FileEditor fileEditor, JPanel container, TaskRunner runner) {
		this.runner = runner;
		this.editor = ((TextEditor) fileEditor).getEditor();
		this.container = container;
		this.project = project;

		container.addComponentListener(new ComponentAdapter() {
			@Override public void componentResized(ComponentEvent componentEvent) {
				GlancePanel.this.setPreferredSize(new Dimension(GlancePanel.this.container.getWidth() / 10, 0));
				GlancePanel.this.revalidate();
				GlancePanel.this.repaint();
			}
		});

		editor.getDocument().addDocumentListener(new DocumentAdapter() {
			@Override public void documentChanged(DocumentEvent documentEvent) {
				updateImage();
			}
		});

		editor.getScrollingModel().addVisibleAreaListener(this);
		MouseListener listener = new MouseListener();
		addMouseListener(listener);
		addMouseMotionListener(listener);

		this.setMinimumSize(new Dimension(50, 0));
		this.setSize(new Dimension(50, 0));
		setPreferredSize(new Dimension(200, 200));
		for(int i = 0; i < Array.getLength(minimaps); i++) {
			minimaps[i] = new Minimap();
		}
		updateImage();
	}

	/**
	 * Fires off a new task to the worker thread. This should only be called from the ui thread.
	 */
	private void updateImage() {
		synchronized (updatePending) {
			// If we have already sent a rendering job off to get processed then first we need to wait for it to finish.
			// see updateComplete for dirty handling. The is that there will be fast updates plus one final update to
			// ensure accuracy, dropping any requests in the middle.
			if(updatePending) {
				dirty = true;
				return;
			}
			updatePending = true;
		}

		PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(editor.getDocument());
		SyntaxHighlighter hl = SyntaxHighlighterFactory.getSyntaxHighlighter(file.getLanguage(), project, file.getVirtualFile());

		nextBuffer = activeBuffer == 0 ? 1 : 0;

		runner.add(new RenderTask(minimaps[nextBuffer], editor.getDocument().getText(), editor.getColorsScheme(), hl, new Runnable() {
			@Override public void run() {
				updateComplete();
			}
		}));
	}

	private void updateComplete() {
		synchronized (updatePending) {
			updatePending = false;
		}

		if(dirty) {
			SwingUtilities.invokeLater(new Runnable() {
				@Override public void run() {
					updateImage();
					dirty = false;
				}
			});
		}

		activeBuffer = nextBuffer;

		repaint();
	}

	@Override
	public void paint(Graphics g) {
		g.setColor(editor.getColorsScheme().getDefaultBackground());
		g.fillRect(0, 0, getWidth(), getHeight());

		int offset = 0;

		// If the panel is 1:1 then just draw everything in the top left hand corner, otherwise we need to gracefully scroll.
		if(editor.getDocument().getLineCount() * 2 > getHeight()) {
			int firstVisibleLine = editor.xyToLogicalPosition(new Point(0, (int) editor.getScrollingModel().getVisibleArea().getY())).line;
			int lastVisibleLine = editor.xyToLogicalPosition(new Point(0, (int) editor.getScrollingModel().getVisibleArea().getMaxY())).line;
			// Scroll the minimap vertically by the amount that would be off screen scaled to how far through the document we are.
			float percentComplete = firstVisibleLine / (float)(editor.getDocument().getLineCount() - (lastVisibleLine - firstVisibleLine));
			offset = (int) ((editor.getDocument().getLineCount() * 2 - getHeight()) * percentComplete);
		}

		logger.debug(String.format("Rendering to buffer: %d", activeBuffer));
		if(activeBuffer >= 0) {
			Minimap minimap = minimaps[activeBuffer];

			// Draw the image and scale it to stretch vertically.
			g.drawImage(minimap.img,                                                    // source image
					0, 0,     getWidth(), getHeight(),                                  // destination location
					0, offset, getWidth(), offset + getHeight(),                        // source location
					null);                                                              // observer
		}

		// Draw the editor visible area
		Rectangle visible = editor.getScrollingModel().getVisibleArea();
		LogicalPosition top = editor.xyToLogicalPosition(new Point(visible.x, visible.y));
		LogicalPosition bottom = editor.xyToLogicalPosition(new Point(visible.x, visible.y + visible.height));
		int start = top.line * 2 - offset;
		int height = (bottom.line * 2) - start;
		int width = getWidth() - 1;

		g.setColor(Color.GRAY);
		Graphics2D g2d = (Graphics2D) g;
		g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
		g2d.drawRect(0, start, width, height);
		g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.10f));
		g2d.fillRect(0, start, width, height);
	}

	@Override public void visibleAreaChanged(VisibleAreaEvent visibleAreaEvent) {
		// Window should not take up more then 10%
		int percentageWidth = GlancePanel.this.container.getWidth() / 10;
		// but shouldn't be too wide either. 100 chars wide should be enough to visualize a code outline.
		// TODO: This should probably be a config option.
		int totalWidth = Math.min(percentageWidth, MAX_WIDTH);
		setPreferredSize(new Dimension(totalWidth, 0));

		repaint();
	}

	protected LogicalPosition getPositionFor(int x, int y) {
		if(x < 0) x = 0;
		if(y < 0) y = 0;
		if(x > getWidth()) x = getWidth();
		if(y > getHeight()) y = getHeight();

		// If the panel is 1:1 then mapping straight to the line that was selected is a good way to go.
		if(minimaps[activeBuffer].height < getHeight()) {
			return new LogicalPosition(y / 2, x);
		} else {
			// Otherwise use the click as the relative position
			return new LogicalPosition((int) (y / (float)getHeight() * minimaps[activeBuffer].height) / 2, x);
		}
	}

	private class MouseListener extends MouseAdapter {
		@Override public void mouseDragged(MouseEvent e) {
			// Disable animation when dragging for better experience.
			editor.getScrollingModel().disableAnimation();
			editor.getScrollingModel().scrollTo(getPositionFor(e.getX(), e.getY()), ScrollType.CENTER);
			editor.getScrollingModel().enableAnimation();
		}

		@Override public void mouseClicked(MouseEvent e) {
			editor.getScrollingModel().scrollTo(getPositionFor(e.getX(), e.getY()), ScrollType.CENTER);
		}

		@Override public void mousePressed(MouseEvent e) {
			editor.getScrollingModel().scrollTo(getPositionFor(e.getX(), e.getY()), ScrollType.CENTER);
		}
	}
}
