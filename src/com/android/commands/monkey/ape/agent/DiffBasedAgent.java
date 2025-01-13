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
        INITIALIZING, REACHING, EXPLORING, DESPERATE;
    }




    private String logFile;
    private String namespace;

    private Set<String> allActivities = new HashSet<>();
    private Set<State> exploringStates = new HashSet<>();

    private FocusTransition currentTransitionToCheck = null;
    private Map<FocusTransition, Set<LogAction>> existingTransitions = new HashMap<>();
    private Map<FocusTransition, Set<LogAction>> newTransitions = new HashMap<>();

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

        Set<FocusTransition> transitions = parseFocusTransitions(focusSet);


        if (transitions.isEmpty()) {
            Logger.println("No focus activities, can stop execution");
        } else {

            buildATGFromLog(previousLog);

            for (FocusTransition ft : transitions) {
                Set<LogAction> actionsToCheck = getExistingActionsToCheck(ft);
                if (!actionsToCheck.isEmpty()) {
                    this.existingTransitions.put(ft, actionsToCheck);
                } else {
                    this.newTransitions.put(ft, getExistingActionsToSkip(ft));
                }
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

    }

    private static Set<FocusTransition> parseFocusTransitions(String focusSet) {
        Set<FocusTransition> transitions = new HashSet<>();
        for (String transitionString : focusSet.split(",")) {
            String[] split = transitionString.split("_");
            FocusTransition t = new FocusTransition();
            t.source = split[0];
            t.target = split[1];
            transitions.add(t);
        }
        return transitions;
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

    private Set<LogAction> getExistingActionsToCheck(FocusTransition ft) {
        Set<LogAction> actionsToCheck = new HashSet<LogAction>();
        for (LogActivity source : graph.keySet()) {
            if (source.activity.equals(ft.source)) {
                for (LogActivity target : graph.get(source).keySet()) {
                    if (target.activity.equals(ft.target)) {
                        actionsToCheck.addAll(graph.get(source).get(target));
                    }
                }
            }
        }
        return actionsToCheck;
    }

    private Set<LogAction> getExistingActionsToSkip(FocusTransition ft) {
        Set<LogAction> actionsToSkip = new HashSet<LogAction>();
        for (LogActivity source : graph.keySet()) {
            if (source.activity.equals(ft.source)) {
                for (LogActivity target : graph.get(source).keySet()) {
                    actionsToSkip.addAll(graph.get(source).get(target));
                }
            }
        }
        return actionsToSkip;
    }

    private FocusTransition nextToCheck(String source) {

        for (FocusTransition transition : existingTransitions.keySet()) {
            if (transition.source.equals(source)) {
                return transition;
            }
        }
        for (FocusTransition transition : newTransitions.keySet()) {
            if (transition.source.equals(source)) {
                return transition;
            }
        }
        return null;
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
        List<LogActivity> candidates = new ArrayList<>();

        Logger.format("Current state widgets %s", getWidgets());

        // Find best match for log activity object
        for (LogActivity from : graph.keySet()) {

            if (!from.activity.equals(activityName)) {
                continue;
            }

            boolean noInvalid = true;
            boolean hasValid = false;
            for (LogActivity to : graph.get(from).keySet()) {
                for (LogAction action : graph.get(from).get(to)) {

                    if (modelActionFromLogAction(action) == null) {
                        noInvalid = false;
                        break;
                    } else {
                        hasValid = true;
                    }
                }
                if (!noInvalid) {
                    break;
                }

            }

            if (noInvalid && hasValid) {

                LogActivity fromState = logActivityFromState(newState);
                if (!invalidMapping.containsKey(fromState) || !invalidMapping.get(fromState).contains(from)) {
                    candidates.add(from);
                }
            }
        }

        Logger.format("# of candidate activities for %s is %d", activityName, candidates.size());

        return candidates;
    }

    private Set<LogActivity> getActivitiesToReach() {
        Set<LogActivity> toReach = new HashSet<>();

        for (FocusTransition transition : existingTransitions.keySet()) {
            for (LogAction action : existingTransitions.get(transition)) {
                if (!checkedActivities.contains(action.source)) {
                    toReach.add(action.source);
                }
            }
        }
        for (FocusTransition transition : newTransitions.keySet()) {
            for (LogAction action : newTransitions.get(transition)) {
                if (!checkedActivities.contains(action.source)) {
                    toReach.add(action.source);
                }
            }
        }
        return toReach;
    }

    private List<Action> candidateActions(List<String> desiredNextActivities) {
        String currActivity = newState.getActivity();

        List<Action> candidates = new ArrayList<>();

        for (ModelAction modelAction : newState.targetedActions()) {

            String modelResourceId = getResourceId(modelAction.getTarget().toXPath());

            for (LogActivity from : graph.keySet()) {
                if (from.activity.equals(currActivity)) {
                    for (LogActivity to : graph.get(from).keySet()) {
                        if (desiredNextActivities.contains(to.activity)) {
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

    private Action reachingModeAction() {

        if (!this.currentLogActivity.activity.equals(newState.getActivity())) {

            LogActivity fromState = logActivityFromState(currentState);
            if (!invalidMapping.containsKey(fromState)) {
                invalidMapping.put(fromState, new HashSet<LogActivity>());
            }
            LogActivity previousLogActivity = logActivityPath.get(logActivityPath.indexOf(currentLogActivity)-1);
            invalidMapping.get(fromState).add(previousLogActivity);


            Logger.format("Activity %s from the path is invalid according to state %s, looking for another path", currentLogActivity, newState.getActivity());
            return findNextPath();
        }

        List<Action> candidates = candidateActions(Arrays.asList(nextLogActivity.activity));
        Logger.format("Source=%s; Target=%s; Candidate size=%d", currentLogActivity, nextLogActivity, candidates.size());

        for (Action action : candidates) {

            Logger.format("Source=%s; Target=%s; Action=%s; Xpath=%s",
                    currentLogActivity, nextLogActivity, action.getType(), action.getTarget().toXPath());


            if (!action.isVisited()) {

                this.currentLogActivity = nextLogActivity;
                if (logActivityPath.indexOf(currentLogActivity)+1 < logActivityPath.size()) {
                    this.nextLogActivity = logActivityPath.get(logActivityPath.indexOf(currentLogActivity)+1);
                } else {
                    this.nextLogActivity = null;
                }

                return action;
            }

        }

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

    private LogActivity logActivityFromState(State state) {
        LogActivity logActivity = new LogActivity();
        logActivity.activity = state.getActivity();
        logActivity.id = state.getGraphId();
        return logActivity;
    }

    private Action preparePath(List<LogActivity> path, LogActivity target, boolean desperate) {

        Logger.format("Found next path from source activity %s to new target activity %s", newState.getActivity(), target);
        this.logActivityPath = path;
        Logger.format("The path is %s", path);

        if (newState.getActivity().equals(target.activity)) {
            mode = mode.EXPLORING;
            return exploringModeAction();
        } else {
            this.mode = desperate ? Mode.DESPERATE : Mode.REACHING;
            this.currentLogActivity = path.get(0);
            this.nextLogActivity = path.get(1);
            return reachingModeAction();
        }

    }

    private Action bfs(LogActivity current, Set<LogActivity> targets, boolean desperate) {

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
                return preparePath(curr, lastActivity, desperate);
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

    private Action findNextPathIterative(List<LogActivity> sources, Set<LogActivity> targets, boolean desperate) {

        Action nextPathAction = null;
        int index = 0;
        do {

            LogActivity current = sources.get(index);
            index++;
            Logger.format("Choosing %s as current log activity", current);

            nextPathAction = bfs(current, targets, desperate);

            // If no choice left, add current log activity to the invalid map
            if (nextPathAction == null && desperate) {
                LogActivity fromState = logActivityFromState(newState);
                if (!invalidMapping.containsKey(fromState)) {
                    invalidMapping.put(fromState, new HashSet<LogActivity>());
                }
                invalidMapping.get(fromState).add(current);
            }

        } while (nextPathAction == null && index < sources.size());

        return nextPathAction;
    }

    private Action findNextPath() {

        List<LogActivity> activities = stateToActivities();

        if (activities.isEmpty()) {
            Logger.format("Could not map state %s to activity from log, restarting", newState.getActivity());
            return initialize();
        }

        Action nextPathAction = findNextPathIterative(activities, getActivitiesToReach(), false);

        if (nextPathAction != null) {
            return nextPathAction;
        }

        // Don't give up, look for MainActivity
        nextPathAction = findNextPathIterative(activities, activitiesByKeyword("MainActivity"), true);

        if (nextPathAction == null) {
            Logger.format("Could not find path from source activity %s to any target, restarting", newState.getActivity());
            return initialize();
        }

        return nextPathAction;
    }


    private Action assignTransitionToCheck() {

        Logger.format("Trying to assign transition to check from existing transitions %s", existingTransitions.keySet());

        // Assign new transition to check
        // First check existing transitions
        for (FocusTransition transition : existingTransitions.keySet()) {
            if (transition.source.equals(newState.getActivity())) {
                for (LogAction logAction : existingTransitions.get(transition)) {
                    ModelAction modelAction = modelActionFromLogAction(logAction);
                    if (modelAction != null && !modelAction.isVisited()) {
                        this.currentTransitionToCheck = transition;
                        return modelAction;
                    }
                }
            }
        }

        Logger.format("Trying to assign transition to check from new transitions %s", newTransitions.keySet());

        // Next check new transitions
        for (FocusTransition transition : newTransitions.keySet()) {
            if (transition.source.equals(newState.getActivity())) {

                for (ModelAction modelAction : newState.targetedActions()) {
                    String modelActionResourceId = getResourceId(modelAction.getTarget().toXPath());

                    boolean found = false;
                    for (LogAction logAction : newTransitions.get(transition)) {
                        String resourceId = getResourceId(logAction.targetXpath);

                        if (modelActionResourceId.equals(resourceId) &&
                                logAction.actionType.equals(modelAction.getType())) {

                            found = true;
                            break;
                        }
                    }

                    if (!found && !modelAction.isVisited()) {
                        this.currentTransitionToCheck = transition;
                        return modelAction;
                    }
                }
            }
        }

        return null;
    }


    private Action exploringModeAction() {

        if (currentTransitionToCheck != null) {

            Logger.format("Current transition to check is %s", currentTransitionToCheck);

            // Found a transition, there is an edge in the ATG
            if (newState.getActivity().equals(currentTransitionToCheck.target)) {

                Logger.format("Transition found between %s and %s!", currentTransitionToCheck.source, currentTransitionToCheck.target);

                if (existingTransitions.containsKey(currentTransitionToCheck)) {
                    existingTransitions.remove(currentTransitionToCheck);
                }
                if (newTransitions.containsKey(currentTransitionToCheck)) {
                    newTransitions.remove(currentTransitionToCheck);
                }

                currentTransitionToCheck = null;
            } else {
                Logger.format("Target differs from transition target, moving to next transition");
            }
        }

        Action action = assignTransitionToCheck();

        // New transition to check from same activity
        if (action != null) {
            return action;
        }

        checkedActivities.add(logActivityPath.get(logActivityPath.size() - 1));
        Logger.format("Current source %s does not match any new transition to check, looking for a next path", newState.getActivity());

        // Choose a new focus action and start again
        if (!getActivitiesToReach().isEmpty()) {
            return findNextPath();
        } else {
            return null; // Finished?
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

        if (mode == Mode.REACHING || mode == Mode.DESPERATE) {

            Logger.format("Looking for target", target);

            if (target.activity.equals(newState.getActivity())) {

                if (mode == Mode.REACHING) {
                    Logger.format("Reached target activity %s, moving to mode EXPLORING", target);
                    mode = Mode.EXPLORING;
                    return exploringModeAction();
                }
                if (mode == Mode.DESPERATE) {
                    Logger.format("Desperately reached an activity %s from which we can find next path", target);
                    return findNextPath();
                }
            }

            return reachingModeAction();
        }

        if (mode == Mode.EXPLORING) {
            return exploringModeAction();
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