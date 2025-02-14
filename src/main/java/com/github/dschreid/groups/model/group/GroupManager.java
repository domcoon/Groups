package com.github.dschreid.groups.model.group;

import com.github.dschreid.groups.GroupsPlugin;
import com.github.dschreid.groups.PrefixedExceptionBuilder;
import com.github.dschreid.groups.lang.LangKeys;
import com.github.dschreid.groups.model.AbstractManager;
import com.github.dschreid.groups.model.PermissionManager;
import com.github.dschreid.groups.model.node.*;
import com.github.dschreid.groups.model.user.User;
import com.github.dschreid.groups.storage.Storage;
import com.github.dschreid.groups.util.PermissionAssist;
import com.github.dschreid.groups.view.GroupView;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

public class GroupManager extends AbstractManager<String, Group> implements PermissionManager {
    public static final GroupNode DEFAULT_GROUP = new GroupNode("default", 0L);

    private final Storage storage;
    private final GroupsPlugin plugin;

    public GroupManager(Storage storage, GroupsPlugin plugin) {
        this.storage = storage;
        this.plugin = plugin;
        this.getOrCreate(DEFAULT_GROUP.getGroup());
    }

    @Override
    protected Group apply(String key) {
        return new Group(key);
    }

    @Override
    protected String normalize(String key) {
        return key.toLowerCase();
    }

    public CompletableFuture<Group> createAndLoad(String name) {
        String key = name.toLowerCase();
        if (contains(key)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_EXIST)
                    .createPrefixedException();
        }
        return storage.createAndLoadGroup(name);
    }

    public void sendGroupsView(CommandSender sender, GroupView view) {
        view.sendView(sender, this.getAll());
    }

    public CompletableFuture<Void> setGroup(String player, String group, long expire) {
        if (!contains(group)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }
        return this.plugin
                .getUserManager()
                .loadUser(player)
                .thenApply(
                        user -> {
                            if (user == null) {
                                throw new PrefixedExceptionBuilder()
                                        .setMessage(LangKeys.USER_NOT_EXISTS)
                                        .createPrefixedException();
                            }
                            this.clearGroups(user);
                            return user;
                        })
                .thenCompose(user -> this.addGroup(user, group, expire));
    }

    private CompletableFuture<Void> clearGroups(User user) {
        Collection<Node> matching = user.getPermissionCache().getMatching(GroupNode.REGEX);
        for (Node node : matching) {
            PermissionAssist.removePermission(user, node.getPermission());
        }
        return this.storage.saveUser(user);
    }

    public CompletableFuture<Void> addGroup(String player, String group, long expire) {
        return this.plugin
                .getUserManager()
                .loadUser(player)
                .thenCompose(
                        (user) -> {
                            if (user == null) {
                                throw new PrefixedExceptionBuilder()
                                        .setMessage(LangKeys.USER_NOT_EXISTS)
                                        .createPrefixedException();
                            }
                            return addGroup(user, group, expire);
                        });
    }

    public CompletableFuture<Void> addGroup(User user, String group, long duration) {
        GroupNode storedGroup = user.getStoredGroup();
        if (storedGroup != null
                && !storedGroup.isExpired()
                && group.equalsIgnoreCase(storedGroup.getGroup())) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.USER_GROUP_ALREADY_HAS)
                    .createPrefixedException();
        }

        if (!contains(group)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }

        GroupNode groupNode = new GroupNode(group);
        if (duration > 0) {
            groupNode.withDuration(duration);
        }

        return plugin.getUserManager().setPermission(user, groupNode.toPermissionNode());
    }

    public CompletableFuture<Void> removeGroup(String target, String group) {
        if (!contains(group)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }

        return this.plugin
                .getUserManager()
                .loadUser(target)
                .thenCompose(
                        user -> {
                            if (user == null) {
                                throw new PrefixedExceptionBuilder()
                                        .setMessage(LangKeys.USER_NOT_EXISTS)
                                        .createPrefixedException();
                            }
                            return PermissionAssist.removePermission(
                                    user, new GroupNode(group).toPermissionNode().getPermission());
                        });
    }

    public Group getGroup(Player player) {
        User user = this.plugin.getUserManager().getUser(player);
        return this.getGroup(user);
    }

    public Group getGroup(User user) {
        return get(getGroupNode(user).getGroup());
    }

    public GroupNode getGroupNode(User user) {
        GroupNode storedGroup = user.getStoredGroup();
        if (storedGroup != null && contains(storedGroup.getGroup()) && !storedGroup.isExpired()) {
            return storedGroup;
        }

        Collection<Node> nodes = user.getPermissionCache().getStartingWith("group.");
        if (nodes.isEmpty()) {
            user.setStoredGroup(DEFAULT_GROUP);
            return GroupManager.DEFAULT_GROUP;
        }

        GroupNode bestGroup =
                nodes.stream()
                        .map(GroupNode::new)
                        .filter(groupNode -> groupNode != DEFAULT_GROUP && contains(groupNode.getGroup()))
                        .max(Comparator.comparingInt(this::getWeight))
                        .orElse(DEFAULT_GROUP);

        user.setStoredGroup(bestGroup);
        return bestGroup;
    }

    private int getWeight(GroupNode node) {
        Group group = get(node.getGroup());
        return group == null ? -1 : group.getWeight();
    }

    @Override
    public CompletableFuture<Void> setPermission(
            String subject, String permission, boolean value, long expiring) {
        if (!contains(subject)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }

        Group group = get(subject);
        Node build = new NodeBuilder(permission, value).duration(expiring).build();
        return setPermission(group, build);
    }

    public CompletableFuture<Void> setPermission(Group group, Node node) {
        return PermissionAssist.setPermission(group, node)
                .thenCompose((ignored) -> this.storage.saveGroup(group))
                .whenComplete((unused, throwable) -> this.plugin.getUserManager().invalidateAll());
    }

    @Override
    public CompletableFuture<Void> removePermission(String subject, String permission) {
        if (!contains(subject)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }

        Group group = get(subject);
        return PermissionAssist.removePermission(group, permission)
                .thenCompose((ignored) -> this.storage.saveGroup(group));
    }

    public CompletableFuture<Void> deleteGroup(String name) {
        if (!contains(name)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }
        remove(name);
        this.storage.removeNodeEverywhere("group.%s".formatted(name.toLowerCase()));
        return storage
                .deleteGroup(name)
                .whenComplete(
                        (unused, throwable) -> {
                            if (DEFAULT_GROUP.getGroup().equalsIgnoreCase(name)) {
                                storage.createAndLoadGroup(GroupManager.DEFAULT_GROUP.getGroup());
                            }
                        });
    }

    public void clearPrefix(String name) {
        if (!contains(name)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }

        Group group = get(name);

        Collection<Node> matching = group.getPermissionCache().getMatching(PrefixNode.PREFIX_REGEX);
        matching.forEach(node -> PermissionAssist.removePermission(group, node.getPermission()));
        group.invalidate();
    }

    /**
     * Adds the prefix to the existing table of prefixes^
     */
    public CompletableFuture<Void> addPrefix(String group, String prefix, int weight) {
        checkPrefix(prefix);
        Node prefixNode = new PrefixNode(weight, prefix).toPermissionNode();
        return setPermission(group, prefixNode.getPermission(), true, 0);
    }

    /**
     * Adds the prefix and clears all the previous prefixes
     *
     * @return
     */
    public CompletableFuture<Void> setPrefix(String group, String prefix) {
        checkPrefix(prefix);
        clearPrefix(group);
        return addPrefix(group, prefix, 0);
    }

    private void checkPrefix(String prefix) {
        if (prefix.length() < 1 || prefix.length() > 16) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.INVALID_PREFIX)
                    .createPrefixedException();
        }
    }

    /**
     * Adds the prefix and clears all the previous prefixes
     *
     * @return
     */
    public CompletableFuture<Void> setWeight(String target, int weight) {
        if (!contains(target)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }
        Group group = get(target);
        return clearWeight(group)
                .thenCompose(unused -> addWeight(group, weight));
    }

    public CompletableFuture<Void> clearWeight(String group) {
        if (!contains(group)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }
        return this.clearWeight(get(group));
    }

    public CompletableFuture<Void> clearWeight(Group group) {
        Collection<Node> matching = group.getPermissionCache().getMatching(WeightNode.REGEX);
        for (Node node : matching) {
            PermissionAssist.removePermission(group, node.getPermission());
        }
        return this.storage.saveGroup(group);
    }

    public CompletableFuture<Void> addWeight(String group, int weight) {
        if (!contains(group)) {
            throw new PrefixedExceptionBuilder()
                    .setMessage(LangKeys.GROUP_DOES_NOT_EXIST)
                    .createPrefixedException();
        }
        return addWeight(get(group), weight);
    }

    public CompletableFuture<Void> addWeight(Group group, int weight) {
        Node node = new WeightNode(weight).toPermissionNode();
        return PermissionAssist.setPermission(group, node)
                .thenCompose((unused) -> this.storage.saveGroup(group));
    }
}
