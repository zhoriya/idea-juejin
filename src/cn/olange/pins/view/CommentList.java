package cn.olange.pins.view;

import cn.olange.pins.model.*;
import cn.olange.pins.service.PinsService;
import cn.olange.pins.setting.JuejinPersistentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.SimpleTree;
import com.intellij.util.Alarm;
import com.intellij.util.ui.JBUI;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

public class CommentList extends JPanel implements Disposable {
	private final Project project;
	private final String pinID;
	private JsonArray commentData = new JsonArray();
	private int pageSize = 20;
	private int pageNum = 1;
	private String cursor = "0";
	private Alarm commentAlarm;
	private SimpleTree jxTree;
	private DefaultMutableTreeNode root;
	private Map<String, String> commentReplyCursorMap = new HashMap<>();
	private JButton replyPanel;
	private DefaultMutableTreeNode selectNode;

	public CommentList(Project project, String pinID) {
		super();
		this.project = project;
		this.pinID = pinID;
		this.commentAlarm = new Alarm();
		this.initData();
	}

	private void initData() {
		this.setLayout(new BorderLayout());
		JTextField textField = new JTextField();
		JPanel bottomPanel = new JPanel(new BorderLayout());
		replyPanel = new JButton();
		replyPanel.setLayout(new BorderLayout());
		replyPanel.add(new ReplyUserActionButton(new ReplyUserActionButton.ClearAction() {
			@Override
			public void actionPerformed(@NotNull AnActionEvent anActionEvent) {
				replyPanel.setVisible(false);
				selectNode = null;
			}
		}), BorderLayout.EAST);
		bottomPanel.add(replyPanel, BorderLayout.WEST);
		replyPanel.setVisible(false);

		bottomPanel.add(textField, BorderLayout.CENTER);
		JButton commentBtn = new JButton("评论");
		Config config = JuejinPersistentConfig.getInstance().getState();
		commentBtn.addActionListener(new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {
				String text = textField.getText();
				if (StringUtils.isNotEmpty(text) && config.isLogined()) {
					commentBtn.setEnabled(false);
					ApplicationManager.getApplication().invokeLater(() -> {
						PinsService pinsService = PinsService.getInstance(project);
						DefaultTreeModel model = (DefaultTreeModel) jxTree.getModel();
						if (selectNode == null || selectNode.getUserObject() == null) {
							JsonObject commentObj = pinsService.comment(pinID, text, config.getCookieValue());
							if (commentObj != null && commentObj.get("data") != null) {
								SwingUtilities.invokeLater(()->{
									root.insert(new DefaultMutableTreeNode(new CommentNode(1, commentObj.get("data").getAsJsonObject())), 0);
									model.reload(root);
								});
							}
						} else {
							JsonObject nodeItem = ((CommentNode) selectNode.getUserObject()).getItem();
							JsonElement reply_info = nodeItem.get("reply_info");
							String commentID, replyID = "", replyUserID = "";
							if (reply_info == null || reply_info.isJsonNull()) {
								commentID = nodeItem.get("comment_id").getAsString();
							} else {
								commentID = reply_info.getAsJsonObject().get("reply_comment_id").getAsString();
								replyID = reply_info.getAsJsonObject().get("reply_id").getAsString();
								replyUserID = reply_info.getAsJsonObject().get("reply_user_iD").getAsString();
							}
							JsonObject replyObj = pinsService.replyComment(pinID, commentID, replyID, replyUserID, text, config.getCookieValue());
							if (replyObj != null && replyObj.get("data") != null) {
								SwingUtilities.invokeLater(() -> {
									JsonObject reply = replyObj.get("data").getAsJsonObject();
									DefaultMutableTreeNode commentNode = new DefaultMutableTreeNode(new CommentNode(2, reply));
									((DefaultMutableTreeNode) selectNode.getParent()).add(commentNode);
									model.nodeStructureChanged(selectNode.getParent());
								});
							}
						}
						SwingUtilities.invokeLater(()->{
							replyPanel.setVisible(false);
							cursor = "0";
							textField.setText("");
							commentBtn.setEnabled(true);
						});
					});
				}
			}
		});
		if (!config.isLogined()) {
			commentBtn.setEnabled(false);
			commentBtn.setToolTipText("未登录");
		}
		bottomPanel.add(commentBtn, BorderLayout.EAST);
		this.add(bottomPanel, BorderLayout.NORTH);
		jxTree = new SimpleTree();
		JBScrollPane scrollPane = new JBScrollPane(this.jxTree){
			public Dimension getMinimumSize() {
				Dimension size = super.getMinimumSize();
				size.height = jxTree.getHeight();
				return size;
			}
		};
		scrollPane.setBorder(JBUI.Borders.empty());
		this.add(scrollPane, BorderLayout.CENTER);
		jxTree.setOpaque(false);
		jxTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
		jxTree.setRootVisible(false);
		jxTree.getEmptyText().clear();
		root = new DefaultMutableTreeNode("");
		jxTree.setModel(new DefaultTreeModel(root));
		this.jxTree.setCellRenderer(new CommentTreeCellRender());
		(new DoubleClickListener() {
			protected boolean onDoubleClick(MouseEvent event) {
				if (event.getSource() != CommentList.this.jxTree) {
					return false;
				} else {
					TreePath selectionPath = jxTree.getSelectionPath();
					if (selectionPath == null) {
						return false;
					}
					DefaultMutableTreeNode node = (DefaultMutableTreeNode) selectionPath.getLastPathComponent();
					if (node.getUserObject() instanceof NeedMore) {
						TreePath parentPath = selectionPath.getParentPath();
						DefaultMutableTreeNode parentNode = (DefaultMutableTreeNode) parentPath.getLastPathComponent();
						parentNode.remove(node);
						if (parentNode.getUserObject() instanceof CommentNode) {
							CommentNode treeNode = (CommentNode) parentNode.getUserObject();
							getCommentReply(treeNode, parentNode);
						} else {
							loadMoreComment(parentNode);
						}
					} else if (node.getUserObject() instanceof CommentNode) {
						selectNode = node;
						JsonObject item = ((CommentNode) node.getUserObject()).getItem();
						replyPanel.setText("回复 " + item.get("user_info").getAsJsonObject().get("user_name").getAsString());
						replyPanel.setVisible(true);
					}
					return true;
				}
			}
		}).installOn(this.jxTree);
		scheduleComment(root);
	}

	private void loadMoreComment(DefaultMutableTreeNode parentNode) {
		ApplicationManager.getApplication().invokeLater(()->{
			this.scheduleComment(parentNode);
		});
	}

	private void getCommentReply(CommentNode treeNode, DefaultMutableTreeNode parentNode) {
		JsonObject item = treeNode.getItem();
		String reply_comment_id = item.get("comment_id").getAsString();
		String replyCursor = commentReplyCursorMap.get(reply_comment_id);
		if (this.commentAlarm != null && !this.commentAlarm.isDisposed()) {
			this.commentAlarm.cancelAllRequests();
			if (StringUtils.isEmpty(replyCursor)) {
				this.commentAlarm.addRequest(() -> getCommentReply(reply_comment_id, "0", parentNode), 100);
			}else {
				this.commentAlarm.addRequest(() -> getCommentReply(reply_comment_id, replyCursor, parentNode), 100);
			}
		}

	}

	public void scheduleComment(DefaultMutableTreeNode parentNode) {
		if (this.commentAlarm != null && !this.commentAlarm.isDisposed()) {
			this.commentAlarm.cancelAllRequests();
			if (parentNode == null) {
				root = new DefaultMutableTreeNode("");
				jxTree.setModel(new DefaultTreeModel(root));
				this.commentAlarm.addRequest(() -> findComments(root), 300);
			} else {
				this.commentAlarm.addRequest(()-> findComments(parentNode), 300);
			}
		}
	}

	private void findComments(DefaultMutableTreeNode parentNode) {
		final ModalityState state = ModalityState.current();
		this.commentAlarm.cancelAllRequests();
		ApplicationManager.getApplication().invokeLater(() -> {
			jxTree.setPaintBusy(true);
			PinsService instance = PinsService.getInstance(project);
			instance.getComments(cursor, pageSize, pinID, result -> {
				if (result.isSuccess()) {
					SwingUtilities.invokeLater(() -> {
						JsonObject commentResult = (JsonObject) result.getResult();
						cursor = commentResult.get("cursor").getAsString();
						JsonArray comments = commentResult.getAsJsonArray("data");
						DefaultTreeModel treeMode = (DefaultTreeModel) jxTree.getModel();
						for (int i = 0; i < comments.size(); i++) {
							JsonObject comment = comments.get(i).getAsJsonObject();
							DefaultMutableTreeNode commentNode = new DefaultMutableTreeNode(new CommentNode(1, comment));
							JsonArray reply_infos = comment.getAsJsonArray("reply_infos");
							if (reply_infos != null) {
								String comment_id = comment.get("comment_id").getAsString();
								commentReplyCursorMap.put(comment_id, reply_infos.size() + "");
								int replyCout = comment.get("comment_info").getAsJsonObject().get("reply_count").getAsInt();
								for (JsonElement reply_info : reply_infos) {
									commentNode.add(new DefaultMutableTreeNode(new CommentNode(2, reply_info.getAsJsonObject())));
								}
								if (replyCout > reply_infos.size()) {
									commentNode.add(new DefaultMutableTreeNode(new NeedMore()));
								}
							}
							parentNode.add(commentNode);
						}
						if (commentResult.get("has_more").getAsBoolean()) {
							parentNode.add(new DefaultMutableTreeNode(new NeedMore()));
						}
						treeMode.reload(parentNode);
						jxTree.setPaintBusy(false);
					});
				} else {
					jxTree.getEmptyText().setText("数据获取失败");
				}
			});
		}, state);
	}

	private void getCommentReply(String commentID, String replyCursor, DefaultMutableTreeNode parentNode) {
		final ModalityState state = ModalityState.current();
		this.commentAlarm.cancelAllRequests();
		ApplicationManager.getApplication().invokeLater(() -> {
			jxTree.setPaintBusy(true);
			PinsService instance = PinsService.getInstance(project);
			instance.getCommentReply(replyCursor, pageSize, commentID, pinID, result -> {
				if (result.isSuccess()) {
					SwingUtilities.invokeLater(() -> {
						JsonObject commentResult = (JsonObject) result.getResult();
						String newReplyCursor = commentResult.get("cursor").getAsString();
						commentReplyCursorMap.put(commentID, newReplyCursor);
						JsonArray commentReply = commentResult.getAsJsonArray("data");
						DefaultTreeModel treeMode = (DefaultTreeModel) jxTree.getModel();
						for (int i = 0; i < commentReply.size(); i++) {
							JsonObject reply = commentReply.get(i).getAsJsonObject();
							DefaultMutableTreeNode commentNode = new DefaultMutableTreeNode(new CommentNode(2, reply));
							parentNode.add(commentNode);
						}
						if (commentResult.get("has_more").getAsBoolean()) {
							parentNode.add(new DefaultMutableTreeNode(new NeedMore()));
						}
						treeMode.nodeStructureChanged(parentNode);
						jxTree.setPaintBusy(false);
					});
				}
			});
		}, state);
	}

	@Override
	public void dispose() {
	}
}
