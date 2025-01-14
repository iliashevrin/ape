package com.android.commands.monkey.ape.agent;

import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.util.*;
import java.io.*;
import com.android.commands.monkey.ape.*;
import com.android.commands.monkey.ape.model.*;
import com.android.commands.monkey.ape.agent.StatefulAgent;
import com.android.commands.monkey.ape.naming.Name;
import com.android.commands.monkey.ape.tree.GUITree;
import com.android.commands.monkey.ape.tree.GUITreeBuilder;
import com.android.commands.monkey.ape.tree.GUITreeNode;
import com.android.commands.monkey.ape.utils.XPathBuilder;
import com.android.commands.monkey.ape.utils.RandomHelper;
import com.android.commands.monkey.MonkeySourceApe;
import com.android.commands.monkey.ape.utils.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;

import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Node;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.content.ComponentName;

public class DiffBasedAgent extends StatefulAgent {


    public static final java.lang.String XPATH_PATTERN = ".*class=([^;]*);(resource-id=([^;]*);)?(.*);";

    public static class FocusTransition {
        String source;
        String target;
        @Override
        public String toString() {
            return source + " -> " + target;
        }
        @Override
        public int hashCode() {
            return Objects.hash(source, target);
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            FocusTransition other = (FocusTransition) obj;
            return Objects.equals(source, other.source) && Objects.equals(target, other.target);
        }
    }


    public static class LogActivity {
        @Override
        public String toString() {
            return "[" + activity + ", " + id + "]";
        }
        @Override
        public int hashCode() {
            return Objects.hash(activity, id);
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            LogActivity other = (LogActivity) obj;
            return Objects.equals(activity, other.activity) && Objects.equals(id, other.id);
        }
        String activity;
        String id;


    }


    public static class LogAction {
        @Override
        public String toString() {
            return "[" + source + " -> " + target + ", " + actionType + ", " + targetXpath + "]";
        }
        ActionType actionType;
        String targetXpath;
        LogActivity source;
        LogActivity target;

        @Override
        public int hashCode() {
            return Objects.hash(actionType, source, target, targetXpath);
        }
        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            LogAction other = (LogAction) obj;
            return Objects.equals(actionType, other.actionType) && Objects.equals(source, other.source)
                    && Objects.equals(target, other.target) && Objects.equals(targetXpath, other.targetXpath);
        }

    }


    public static enum Mode {
        INITIALIZING, REACHING, CHECKING;
    }




    private String logFile;
    private String namespace;

    private Set<String> allActivities = new HashSet<>();

    private FocusTransition transition = null;
    private Map<LogActivity, Set<LogAction>> existingActions = new HashMap<>();
    private Map<LogActivity, Set<LogAction>> toSkipActions = new HashMap<>();

    private Mode mode = Mode.INITIALIZING;

    private List<LogActivity> logActivityPath = new ArrayList<>();

    private LogActivity currentLogActivity = null;
    private LogActivity nextLogActivity = null;

    // Activity -> Activity -> Set of Actions
    private Map<LogActivity, Map<LogActivity, Set<LogAction>>> graph = new HashMap<>();

    private Set<LogActivity> checkedActivities = new HashSet<>();

    private Map<LogActivity, Set<LogActivity>> invalidMapping = new HashMap<>();




    public DiffBasedAgent(MonkeySourceApe ape, Graph graph, String previousLog, String manifestFile, String focusSet) {
        super(ape, graph);
        this.logFile = previousLog;

        buildATGFromLog(previousLog);
        this.transition = parseFocusTransition(focusSet);
        this.existingActions = getExistingActions();
        if (existingActions.isEmpty()) {
            this.toSkipActions = getActionsToSkip();
        }

        try {
            Document manifest = parseManifest(manifestFile);
            this.allActivities = getActivities(manifest);
            this.namespace = getNamespace(manifest);
        } catch (Exception e) {
            e.printStackTrace();
            throw new StopTestingException("Unable to parse manifest XML file");
        }

    }

    private static FocusTransition parseFocusTransition(String str) {
        FocusTransition transition = new FocusTransition();
        String[] split = str.split("_");
        transition.source = split[0];
        transition.target = split[1];
        return transition;
    }

    public static String getResourceId(String xpath) {
        if (xpath == null) {
            return null;
        }

        Pattern p = Pattern.compile("^.*resource-id=\\\"(.*)\\\".*$");
        Matcher m = p.matcher(xpath);
        if (m.matches()) {
            if (m.group(1) != null) {
                return m.group(1);
            }
        }

        return xpath;
    }

    private Map<LogActivity, Set<LogAction>> getExistingActions() {
        Map<LogActivity, Set<LogAction>> existingActions = new HashMap<>();
        for (LogActivity source : graph.keySet()) {
            if (source.activity.equals(transition.source)) {
                existingActions.put(source, new HashSet<LogAction>());
                for (LogActivity target : graph.get(source).keySet()) {
                    if (target.activity.equals(transition.target)) {
                        existingActions.get(source).addAll(graph.get(source).get(target));
                    }
                }
                if (existingActions.get(source).isEmpty()) {
                    existingActions.remove(source);
                }
            }
        }
        return existingActions;
    }

    private Map<LogActivity, Set<LogAction>> getActionsToSkip() {
        Map<LogActivity, Set<LogAction>> toSkip = new HashMap<>();
        for (LogActivity source : graph.keySet()) {
            if (source.activity.equals(transition.source)) {
                toSkip.put(source, new HashSet<LogAction>());
                for (LogActivity target : graph.get(source).keySet()) {
                    toSkip.get(source).addAll(graph.get(source).get(target));
                }
                if (toSkip.get(source).isEmpty()) {
                    toSkip.remove(source);
                }
            }
        }
        return toSkip;
    }


    private void buildATGFromLog(String previousLog) {

        BufferedReader reader;

        try {
            reader = new BufferedReader(new FileReader(previousLog));
            String line = readLine(reader);

            while (line != null) {

                if (line.contains("=== Adding edge...")) {

                    while (!line.contains("Source: ")) {
                        line = readLine(reader);
                    }
                    LogActivity source = parseActivity(line);

                    if (source.activity.endsWith("Activity") && !graph.containsKey(source)) {
                        graph.put(source, new HashMap<LogActivity, Set<LogAction>>());
                    }

                    while (!line.contains("Action: ")) {
                        line = readLine(reader);
                    }
                    ActionType actionType = parseActionType(line);
                    String xpath = parseTargetXpath(line);

                    while (!line.contains("Target: ")) {
                        line = readLine(reader);
                    }
                    LogActivity target = parseActivity(line);


                    // Also add target as node in graph
                    if (target.activity.endsWith("Activity") && !graph.containsKey(target)) {
                        graph.put(target, new HashMap<LogActivity, Set<LogAction>>());
                    }
                    if (actionType != null && xpath != null) {
                        LogAction logAction = new LogAction();
                        logAction.actionType = actionType;
                        logAction.targetXpath = xpath;
                        logAction.source = source;
                        logAction.target = target;
                        if (!graph.get(source).containsKey(target)) {
                            graph.get(source).put(target, new HashSet<LogAction>());
                        }
                        graph.get(source).get(target).add(logAction);
                    }

                }

                line = readLine(reader);

            }


            reader.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String documentToString(Document document) throws TransformerException {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        StringWriter stringWriter = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(stringWriter));
        return stringWriter.toString();
    }



    private static String readLine(BufferedReader reader) throws IOException {

        String line = null;
        do {
            line = cleanLine(reader.readLine());
        } while (line != null && "".equals(line));

        return line;
    }

    private static String parseTargetXpath(String line) {

        String temp = line.split("@")[1];

        // Use regex

        temp = temp.substring(0, temp.indexOf("["));

        Pattern p = Pattern.compile(XPATH_PATTERN);

        Matcher m = p.matcher(temp);

        if (m.matches()) {

            String cls = m.group(1);
            String resourceId = m.group(3) == null ? "" : m.group(3);
            String[] props = m.group(4).split(";");

            if (resourceId.isEmpty()) {
                return null;
            }

            String target = String.format("//*[@class=\"%s\"][@resource-id=\"%s\"]", cls, resourceId);

            for (String prop : props) {

                String propName = prop.split("=")[0];
                String value = prop.split("=")[1];
                target += String.format("[@%s='%s']", propName, value);
            }
            return target;
        }

        return null;

    }

    private static String cleanLine(String line) {
        if (line == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != 0) {
                sb.append((char) line.charAt(i));
            }
        }
        return sb.toString();
    }

    private static LogActivity parseActivity(String line) {
        String temp = line.split("@")[0].substring(5);
        LogActivity afl = new LogActivity();
        afl.activity = temp.substring(temp.lastIndexOf("]") + 1);
        afl.id = temp.substring(temp.indexOf(": ") + 2, temp.indexOf("["));
        return afl;
    }

    private static ActionType parseActionType(String line) {
        String temp = line.split("@")[1];

        for (ActionType type : ActionType.values()) {
            if (temp.contains(type.toString())) {
                return type;
            }
        }
        return null;
    }



    private static Document parseManifest(String manifestXml) throws SAXException, IOException, ParserConfigurationException {

        File manifest = new File(manifestXml);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
//        factory.setValidating(true);
        factory.setIgnoringElementContentWhitespace(true);
        DocumentBuilder builder = factory.newDocumentBuilder();

        return builder.parse(manifest);
    }

    private static String getNamespace(Document manifest) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {

        XPath xPath = XPathFactory.newInstance().newXPath();
        Node node = (Node) xPath.compile("//application").evaluate(manifest, XPathConstants.NODE);
        Node namespaceNode = node.getAttributes().getNamedItem("android:name");
        if (namespaceNode != null) {
            String namespace = namespaceNode.getNodeValue();
            return namespace.substring(0, namespace.lastIndexOf('.')-1);
        }
        else {
            return "";
        }
    }


    private static Set<String> getActivities(Document manifest) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {

        Set<String> activities = new HashSet<>();

        XPath xPath = XPathFactory.newInstance().newXPath();
        NodeList nodeList = (NodeList) xPath.compile("//activity").evaluate(manifest, XPathConstants.NODESET);
        for (int i = 0; i < nodeList.getLength(); i++) {
            activities.add(nodeList.item(i).getAttributes().getNamedItem("android:name").getNodeValue());
        }
        return activities;
    }



    private ModelAction modelActionFromLogAction(LogAction action) {

        String resourceId = getResourceId(action.targetXpath);
        for (ModelAction modelAction : newState.targetedActions()) {
            String modelResourceId = getResourceId(modelAction.getTarget().toXPath());
            if (modelAction.getType().equals(action.actionType) && modelResourceId.equals(resourceId)) {
                return modelAction;
            }
        }

        return null;
    }

    private List<String> getWidgets() {
        List<String> widgets = new ArrayList<>();
        for (ModelAction modelAction : newState.targetedActions()) {
            widgets.add(getResourceId(modelAction.getTarget().toXPath()));
        }
        return widgets;
    }

    private Set<LogActivity> activitiesByKeyword(String keyword) {
        Set<LogActivity> activities = new HashSet<>();

        for (LogActivity activity : graph.keySet()) {
            if (activity.activity.contains(keyword)) {
                activities.add(activity);
            }
        }

        return activities;
    }

    private List<LogActivity> stateToActivities() {

        String activityName = newState.getActivity();
        Logger.format("# of actions for current state %s is %d", activityName, newState.targetedActions().size());
        TreeMap<Double, LogActivity> candidates = new TreeMap<>();

        Logger.format("Current state widgets %s", getWidgets());

        LogActivity fromState = logActivityFromState(newState);

        // Find best match for log activity object
        for (LogActivity from : graph.keySet()) {

            if (!from.activity.equals(activityName)) {
                continue;
            }

            if (invalidMapping.containsKey(fromState) && invalidMapping.get(fromState).contains(from)) {
                continue;
            }

            int total = 0;
            int invalid = 0;

            for (LogActivity to : graph.get(from).keySet()) {
                for (LogAction action : graph.get(from).get(to)) {

                    if (modelActionFromLogAction(action) == null) {
                        invalid += 1;
                    }

                    total += 1;
                }

            }

            if (total > 0 && invalid < total) {
                Logger.format("Adding %s with %f to candidates of %s", from, (double)invalid / total, activityName);
                candidates.put((double)invalid / total, from);
            }
        }

        Logger.format("# of candidate activities for %s is %d", activityName, candidates.size());

        return new ArrayList<>(candidates.values());
    }

    private Set<LogActivity> getActivitiesToReach() {

        Set<LogActivity> toReach = new HashSet<>();
        if (!this.existingActions.isEmpty()) {
            for (LogActivity activity : this.existingActions.keySet()) {
                if (!checkedActivities.contains(activity)) {
                    toReach.add(activity);
                }
            }
        } else {
            for (LogActivity activity : this.toSkipActions.keySet()) {
                if (!checkedActivities.contains(activity)) {
                    toReach.add(activity);
                }
            }
        }
        return toReach;
    }

    private List<Action> candidateActions(String nextActivity) {
        String currActivity = newState.getActivity();

        List<Action> candidates = new ArrayList<>();

        for (ModelAction modelAction : newState.targetedActions()) {

            String modelResourceId = getResourceId(modelAction.getTarget().toXPath());

            for (LogActivity from : graph.keySet()) {
                if (from.activity.equals(currActivity)) {
                    for (LogActivity to : graph.get(from).keySet()) {
                        if (nextActivity.equals(to.activity)) {
                            for (LogAction action : graph.get(from).get(to)) {
                                String resourceId = getResourceId(action.targetXpath);
                                if (resourceId.equals(modelResourceId) && modelAction.getType().equals(action.actionType)) {
                                    candidates.add(modelAction);
                                }
                            }
                        }
                    }
                }
            }
        }

        return candidates;
    }

    private Action chooseActionToReach() {

        Action newAction = null;

        // First check original candidates from graph
        for (LogAction logAction : graph.get(currentLogActivity).get(nextLogActivity)) {
            ModelAction modelAction = modelActionFromLogAction(logAction);
            if (modelAction != null && !modelAction.isVisited()) {
                Logger.format("Original Action=%s; Xpath=%s", modelAction.getType(), modelAction.getTarget().toXPath());
                newAction = modelAction;
            }
        }

        List<Action> candidates = candidateActions(nextLogActivity.activity);
        Logger.format("Candidate size=%d", candidates.size());

        for (Action modelAction : candidates) {
            if (!modelAction.isVisited()) {
                Logger.format("Other Action=%s; Xpath=%s", modelAction.getType(), modelAction.getTarget().toXPath());
                newAction = modelAction;
            }
        }

        if (newAction == null) {

            Logger.format("None of the possible actions from %s to %s are valid", currentLogActivity, nextLogActivity);
            LogActivity fromState = logActivityFromState(newState);
            if (!invalidMapping.containsKey(fromState)) {
                invalidMapping.put(fromState, new HashSet<LogActivity>());
            }
            invalidMapping.get(fromState).add(currentLogActivity);

            // All actions towards current focus activity didn't work
            // Maybe it will be possible to reach it via other changed activities
            return findNextPath();
        }

        this.currentLogActivity = nextLogActivity;
        if (logActivityPath.indexOf(currentLogActivity)+1 < logActivityPath.size()) {
            this.nextLogActivity = logActivityPath.get(logActivityPath.indexOf(currentLogActivity)+1);
        } else {
            this.nextLogActivity = null;
        }

        return newAction;
    }

    private LogActivity logActivityFromState(State state) {
        LogActivity logActivity = new LogActivity();
        logActivity.activity = state.getActivity();
        logActivity.id = state.getGraphId();
        return logActivity;
    }

    private Action preparePath(List<LogActivity> path) {

        this.logActivityPath = path;
        Logger.format("The path is %s", path);
        LogActivity target = path.get(path.size()-1);

        if (path.size() == 1) {
            Logger.format("Preparing path but already in target %s", target);
            return chooseActionToCheck();
        } else {
            this.mode = Mode.REACHING;
            this.currentLogActivity = path.get(0);
            this.nextLogActivity = path.get(1);
            return chooseActionToReach();
        }

    }

    private List<LogActivity> bfs(LogActivity current, Set<LogActivity> targets) {

        Queue<List<LogActivity>> q = new LinkedList<>();
        Set<LogActivity> visited = new HashSet<>();
        q.add(Collections.singletonList(current));

        // Finds next path to nearest activity in focus set that was not yet traversed
        while (!q.isEmpty()) {

            List<LogActivity> curr = q.poll();
            LogActivity lastActivity = curr.get(curr.size()-1);
            visited.add(lastActivity);

            // Find a path towards a target activity
            if (targets.contains(lastActivity)) {
                return curr;
            }

            for (LogActivity next : graph.get(lastActivity).keySet()) {
                if (!visited.contains(next)) {
                    List<LogActivity> newPath = new ArrayList<>(curr);
                    newPath.add(next);
                    q.add(newPath);
                }
            }
        }

        return null;
    }

    private List<LogActivity> findNextPathIterative(List<LogActivity> sources, Set<LogActivity> targets) {

        TreeSet<List<LogActivity>> candidatePaths = new TreeSet<>(new Comparator<List<LogActivity>>() {
            @Override
            public int compare(List<LogActivity> l1, List<LogActivity> l2) {
                return Integer.compare(l1.size(), l2.size());
            }
        });

        for (LogActivity source : sources) {
            Logger.format("Checking %s as current log activity", source);
            List<LogActivity> path = bfs(source, targets);
            if (path != null && validateFirstStepInPath(path)) {
                candidatePaths.add(path);
            } else {
                LogActivity fromState = logActivityFromState(newState);
                if (!invalidMapping.containsKey(fromState)) {
                    invalidMapping.put(fromState, new HashSet<LogActivity>());
                }
                invalidMapping.get(fromState).add(source);
            }
        }

        if (candidatePaths.isEmpty()) {
            return null;
        }

        return new ArrayList<>(candidatePaths).get(0);
    }

    // Path is at least 2 steps long
    private boolean validateFirstStepInPath(List<LogActivity> path) {
        if (path.size() == 1) {
            return true;
        }
        LogActivity first = path.get(0);
        LogActivity second = path.get(1);

        for (LogAction action : graph.get(first).get(second)) {
            if (modelActionFromLogAction(action) != null) {
                return true;
            }
        }

        return false;
    }

    private Action findNextPath() {

        Logger.format("Trying to find next path for %s", newState.getActivity());

        List<LogActivity> activities = stateToActivities();

        if (activities.isEmpty()) {
            Logger.format("Could not map state %s to activity from log, restarting", newState.getActivity());
            return initialize();
        }

        List<LogActivity> bestPath = findNextPathIterative(activities, getActivitiesToReach());

        if (bestPath != null) {
            return preparePath(bestPath);
        }

        Logger.format("Could not find path from source activity %s to any target, picking at random", newState.getActivity());
        mode = Mode.INITIALIZING;
        return selectNewActionRandomly();
    }


    private Action assignTransitionToCheck() {

        if (!existingActions.isEmpty()) {

            Logger.format("printing current targets");
            for (ModelAction modelAction : newState.targetedActions()) {
                String modelResourceId = getResourceId(modelAction.getTarget().toXPath());
                Logger.format(modelResourceId);
            }

            Logger.format("Trying to assign transition to check from existing %s", existingActions.keySet());

            // First try actions from original log activity
            for (LogAction action : existingActions.get(currentLogActivity)) {
                Logger.format("Checking log action %s", action);
                ModelAction modelAction = modelActionFromLogAction(action);
                if (modelAction != null && !modelAction.isVisited()) {
                    return modelAction;
                }
            }

            // Then try all actions
            for (LogActivity activity : existingActions.keySet()) {
                if (activity.equals(currentLogActivity)) continue;
                for (LogAction action : existingActions.get(activity)) {
                    Logger.format("Checking log action %s", action);
                    ModelAction modelAction = modelActionFromLogAction(action);
                    if (modelAction != null && !modelAction.isVisited()) {
                        return modelAction;
                    }
                }
            }

        } else {

            Logger.format("Trying to assign transition to check from outside to skip %s", toSkipActions.keySet());

            // Next check new transitions
            for (ModelAction modelAction : newState.targetedActions()) {
                String modelActionResourceId = getResourceId(modelAction.getTarget().toXPath());

                boolean found = false;
                for (LogActivity activity : toSkipActions.keySet()) {
                    for (LogAction action : toSkipActions.get(activity)) {

                        String resourceId = getResourceId(action.targetXpath);

                        if (modelActionResourceId.equals(resourceId) && action.actionType.equals(modelAction.getType())) {

                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        break;
                    }
                }

                if (!found && !modelAction.isVisited()) {
                    return modelAction;
                }
            }
        }

        return null;
    }


    private Action checkAction() {

        // Found a transition, there is an edge in the ATG
        if (newState.getActivity().equals(transition.target)) {

            Logger.format("Transition found between %s and %s!", transition.source, transition.target);
            return null; // Finished

        } else {
            Logger.format("Target %s differs from transition target %s", newState.getActivity(), transition.target);
        }

        return findNextPath();
    }


    private Action chooseActionToCheck() {

        Action action = assignTransitionToCheck();

        // New transition to check from same activity
        if (action != null) {
            mode = Mode.CHECKING;
            return action;
        }

        checkedActivities.add(logActivityPath.get(logActivityPath.size() - 1));
        Logger.format("Current source %s does not match any new action to check, looking for a next path", newState.getActivity());

        // Choose a new focus action and start again
        if (!getActivitiesToReach().isEmpty()) {
            return findNextPath();
        } else {
            Logger.format("No more activities to check, could it be that the transition is removed from ATG?");
            return null; // Finished and did not find transition
        }

    }

    private Action initialize() {
        mode = Mode.INITIALIZING;
        this.logActivityPath = null;
        this.currentLogActivity = null;
        this.nextLogActivity = null;
        return getStartAction(nextRestartAction());
    }

    private static String getNamespace(String activity) {
        return activity.substring(0, activity.lastIndexOf(".") - 1);
    }

    @Override
    protected Action selectNewActionNonnull() {

        Logger.format("Mode %s, current activity %s", mode, newState.getActivity());

        // For example in cases like LeakLauncherActivity
        if (!getNamespace(newState.getActivity()).startsWith(namespace)) {
            Logger.format("Activity %s outside the namespace, trying to get out", newState.getActivity());
            return initialize();
        }

        if (mode == Mode.INITIALIZING) {
            return findNextPath();
        }

        LogActivity target = logActivityPath.get(logActivityPath.size()-1);

        if (mode == Mode.REACHING) {

            Logger.format("Source=%s; Target=%s", currentLogActivity, nextLogActivity);

            if (!currentLogActivity.activity.equals(newState.getActivity())) {

                LogActivity fromState = logActivityFromState(currentState);
                if (!invalidMapping.containsKey(fromState)) {
                    invalidMapping.put(fromState, new HashSet<LogActivity>());
                }
                LogActivity previousLogActivity = logActivityPath.get(logActivityPath.indexOf(currentLogActivity)-1);
                invalidMapping.get(fromState).add(previousLogActivity);


                Logger.format("Activity %s from the path is invalid according to state %s, looking for another path", currentLogActivity, newState.getActivity());
                return findNextPath();
            }

            if (logActivityPath.get(logActivityPath.size()-1).equals(currentLogActivity)) {

                Logger.format("Reached target activity %s, moving to mode CHECKING", currentLogActivity);
                return chooseActionToCheck();

            }

            return chooseActionToReach();
        }

        if (mode == Mode.CHECKING) {
            return checkAction();
        }

        Logger.println("Null action");

        // Finished the dynamic analysis
        return null;
    }

    @Override
    public void onBufferLoss(State actual, State expected) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public void onRefillBuffer(Subsequence path) {
        throw new RuntimeException("Not implemented");
    }

    @Override
    public String getLoggerName() {
        return "DiffBased";
    }

    @Override
    public void onBadState(int lastBadStateCount, int badStateCounter) {
    }

    @Override
    public boolean onVoidGUITree(int counter) {
        return false;
    }

    @Override
    public void onActivityBlocked(ComponentName blockedActivity) {
    }
}