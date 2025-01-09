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
        INITIALIZING, REACHING, EXPLORING;
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

    // Activity -> Activity -> Set of Actions
    private Map<LogActivity, Map<LogActivity, Set<LogAction>>> graph = new HashMap<>();




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
                    if (actionType != null) {
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


    /*
    Copy from ReplayAgent.java
    Should we extend the class?
     */

    protected Name resolveName(NodeList nodeList) {
        int index = RandomHelper.nextInt(nodeList.getLength());
        Element e = (Element) nodeList.item(index);
        GUITreeNode node = GUITreeBuilder.getGUITreeNode(e);
        if (node == null) {
            return null;
        }
        return node.getXPathName();
    }

    protected Name resolveName(String target) throws XPathExpressionException, TransformerException {
        int retry = 2;
        while (retry--> 0) {
            GUITree guiTree = newState.getLatestGUITree();
            Document guiXml = guiTree.getDocument();
//            Logger.println(documentToString(guiXml));
            XPathExpression targetXPath = XPathBuilder.compileAbortOnError(target);
            NodeList nodesByTarget = (NodeList) targetXPath.evaluate(guiXml, XPathConstants.NODESET);
            Name name;
            if (nodesByTarget.getLength() != 0) {
                name = resolveName(nodesByTarget);
            } else {
                refreshNewState();
                continue;
            }
            if (name != null) {
                return name;
            }
        }
        return null;
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



//    private String getMain() {
//        for (String activity : graph.keySet()) {
//            if (activity.contains("MainActivity")) {
//                return activity;
//            }
//        }
//
//        return null;
//    }


    private List<Set<LogAction>> actionsFromPath(List<LogActivity> path) {

        List<Set<LogAction>> actions = new ArrayList<>();
        for (int i = 0; i < path.size()-1; i++) {
            actions.add(graph.get(path.get(i)).get(path.get(i+1)));
        }
        return actions;
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

    private LogActivity stateToActivity() {

        String activityName = newState.getActivity();
        Logger.format("# of actions for current state %s is %d", activityName, newState.targetedActions().size());
        List<LogActivity> candidates = new ArrayList<>();

        // Find best match for log activity object
        for (LogActivity from : graph.keySet()) {

            if (!from.activity.equals(activityName)) {
                continue;
            }

            Set<LogAction> actions = new HashSet<>();
            for (LogActivity to : graph.get(from).keySet()) {
                actions.addAll(graph.get(from).get(to));
            }

            boolean invalidAction = false;
            for (LogAction action : actions) {

                if (modelActionFromLogAction(action) == null) {
                    invalidAction = true;
                    break;
                }
            }
            if (!invalidAction) {
                Logger.format("candidate is %s", from);
                candidates.add(from);
            }
        }

        Logger.format("# of candidate activities for %s is %d", activityName, candidates.size());

        if (!candidates.isEmpty()) {
            return candidates.get(0);
        }
        return null;
    }

    private Set<LogActivity> getActivitiesToReach() {
        Set<LogActivity> toReach = new HashSet<>();

        for (FocusTransition transition : existingTransitions.keySet()) {
            for (LogAction action : existingTransitions.get(transition)) {
                toReach.add(action.source);
            }
        }
        for (FocusTransition transition : newTransitions.keySet()) {
            for (LogAction action : newTransitions.get(transition)) {
                toReach.add(action.source);
            }
        }
        return toReach;
    }

    private Action reachingModeAction() {

        int current = 0;
        for (int i = 0; i < logActivityPath.size(); i++) {
            if (logActivityPath.get(i).activity.equals(newState.getActivity())) {
                current = i;
            }
        }

        LogActivity thisActivity = logActivityPath.get(current);
        LogActivity nextActivity = logActivityPath.get(current+1);

        Set<LogAction> possibleActions = new HashSet<>(graph.get(thisActivity).get(nextActivity));

        Logger.format("Source=%s; Target=%s; #actions=%d",
                thisActivity.activity, nextActivity.activity, possibleActions.size());

        for (LogAction logAction : possibleActions) {

            Logger.format("Source=%s; Target=%s; Action=%s; Xpath=%s",
                    thisActivity.activity, nextActivity.activity, logAction.actionType, logAction.targetXpath);

            ModelAction modelAction = modelActionFromLogAction(logAction);
            if (modelAction == null) {
                // Remove incorrect action from graph
                graph.get(thisActivity).get(nextActivity).remove(logAction);
            } else {
                if (!modelAction.isVisited()) {
                    return modelAction;
                }
            }
        }

        Logger.format("None of the possible actions from %s to %s are valid", thisActivity.activity, nextActivity.activity);

        // All actions towards current focus activity didn't work
        // Maybe it will be possible to reach it via other changed activities
        return findNextPath();

    }

    private Action findNextPath() {

        LogActivity currentState = stateToActivity();

        if (currentState == null) {
            Logger.format("Could not map state %s to activity from log, restarting", newState.getActivity());
            mode = Mode.INITIALIZING;
            return getStartAction(nextRestartAction());
        }

        Queue<List<LogActivity>> q = new LinkedList<>();
        Set<LogActivity> visited = new HashSet<>();
        q.add(Collections.singletonList(currentState));

        // Finds next path to nearest activity in focus set that was not yet traversed
        while (!q.isEmpty()) {

            List<LogActivity> curr = q.poll();
            LogActivity lastActivity = curr.get(curr.size()-1);
            visited.add(lastActivity);

            // Find a path towards a focus activity that is not erroneous
            if (getActivitiesToReach().contains(lastActivity)) {

                Logger.format("New activity to reach is %s", lastActivity);
                this.logActivityPath = curr;

                mode = Mode.REACHING;
                String target = logActivityPath.get(logActivityPath.size()-1).activity;
                Logger.format("Found next path from source activity %s to new target activity %s", newState.getActivity(), target);

                if (newState.getActivity().equals(target)) {
                    mode = mode.EXPLORING;
                    return exploringModeAction();
                }

                return reachingModeAction();
            }

            for (LogActivity next : graph.get(lastActivity).keySet()) {
                if (!visited.contains(next)) {
                    List<LogActivity> newPath = new ArrayList<>(curr);
                    newPath.add(next);
                    q.add(newPath);
                }
            }
        }

        Logger.format("Could not find path from source activity %s to any target, restarting", newState.getActivity());
        mode = Mode.INITIALIZING;
        return getStartAction(nextRestartAction());
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

            // Choose a new focus action and start again
            if (!getActivitiesToReach().isEmpty()) {
                return findNextPath();
            } else {
                return null; // Finished?
            }
        }

        // Assign new transition to check
        // First check existing transitions
        for (FocusTransition transition : existingTransitions.keySet()) {
            if (transition.source.equals(newState.getActivity())) {
                this.currentTransitionToCheck = transition;

                for (LogAction logAction : existingTransitions.get(transition)) {
                    ModelAction modelAction = modelActionFromLogAction(logAction);
                    if (modelAction != null && !modelAction.isVisited()) {
                        return modelAction;
                    }
                }
            }
        }

        // Next check new transitions
        for (FocusTransition transition : newTransitions.keySet()) {
            if (transition.source.equals(newState.getActivity())) {
                this.currentTransitionToCheck = transition;

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
                        return modelAction;
                    }
                }
            }
        }

        Logger.format("Current source %s does not match any transition, restarting", newState.getActivity());
        mode = Mode.INITIALIZING;

        return getStartAction(nextRestartAction());
    }

    private static String getNamespace(String activity) {
        return activity.substring(0, activity.lastIndexOf(".") - 1);
    }

    @Override
    protected Action selectNewActionNonnull() {

        // For example in cases like LeakLauncherActivity
        if (!getNamespace(newState.getActivity()).startsWith(namespace)) {
            Logger.format("Activity %s outside the namespace, trying to get out", newState.getActivity());
            mode = Mode.INITIALIZING;
            return getStartAction(nextRestartAction());
        }

        if (mode == Mode.INITIALIZING) {
            Logger.format("Mode INITIALIZING, going to find a path to a focus activity from %s", newState.getActivity());
            return findNextPath();
        }

        String target = logActivityPath.get(logActivityPath.size()-1).activity;

        if (mode != Mode.EXPLORING) {
            if (target.equals(newState.getActivity())) {
                Logger.format("Reached target activity %s, moving to mode EXPLORING", target);
                mode = Mode.EXPLORING;
            }
        }

        if (mode == Mode.REACHING) {
            Logger.format("Mode REACHING, trying to reach target activity %s from source activity %s", target, newState.getActivity());
            return reachingModeAction();
        }

        if (mode == Mode.EXPLORING) {
            Logger.format("Mode EXPLORING, now in activity %s", newState.getActivity());
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