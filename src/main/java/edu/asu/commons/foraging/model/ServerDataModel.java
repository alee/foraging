package edu.asu.commons.foraging.model;


import java.awt.Point;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import edu.asu.commons.event.Event;
import edu.asu.commons.event.EventChannel;
import edu.asu.commons.event.EventTypeChannel;
import edu.asu.commons.event.PersistableEvent;
import edu.asu.commons.foraging.bot.Bot;
import edu.asu.commons.foraging.bot.BotIdentifier;
import edu.asu.commons.foraging.conf.RoundConfiguration;
import edu.asu.commons.foraging.event.AddClientEvent;
import edu.asu.commons.foraging.event.ExplicitCollectionModeRequest;
import edu.asu.commons.foraging.event.HarvestFruitRequest;
import edu.asu.commons.foraging.event.HarvestResourceRequest;
import edu.asu.commons.foraging.event.LockResourceRequest;
import edu.asu.commons.foraging.event.MovementEvent;
import edu.asu.commons.foraging.event.RealTimeSanctionRequest;
import edu.asu.commons.foraging.event.ResetTokenDistributionRequest;
import edu.asu.commons.foraging.event.ResourceAddedEvent;
import edu.asu.commons.foraging.event.ResourcesAddedEvent;
import edu.asu.commons.foraging.event.TokenCollectedEvent;
import edu.asu.commons.foraging.event.TokenMovedEvent;
import edu.asu.commons.foraging.event.TokensMovedEvent;
import edu.asu.commons.foraging.event.UnlockResourceRequest;
import edu.asu.commons.foraging.rules.Strategy;
import edu.asu.commons.net.Identifier;

/**
 * 
 * Full server side data model.
 * 
 * 
 * @author Allen Lee, Deepali Bhagvat
 */
public class ServerDataModel extends ForagingDataModel {

    private static final long serialVersionUID = 8166812955398387600L;

    private static final NumberFormat CURRENCY_FORMATTER = NumberFormat.getCurrencyInstance();
    
    private transient Logger logger = Logger.getLogger( getClass().getName() );
    private transient Random random = new Random();
    private transient boolean dirty = false;
    
	// Maps client Identifiers to the GroupDataModel that the client belongs to 
    private final Map<Identifier, GroupDataModel> clientsToGroups = new HashMap<Identifier, GroupDataModel>();

    private Map<Strategy, Integer> imposedStrategyDistribution;

    public ServerDataModel() {
        super(EventTypeChannel.getInstance());
    }

    public ServerDataModel(EventChannel channel) {
        super(channel);
    }

    public boolean isDirty() {
		return dirty;
	}

	public void setDirty(boolean dirty) {
		this.dirty = dirty;
	}

    /**
     * Invoked when we try to reconstruct a server game state given a time-ordered Set of
     * PersistableEvents that was previously saved.  
     */
    public void apply(PersistableEvent event) {
        // now we write the actual game action...
        // iterate through all stored Persistable Actions, executing them onto
        // the ForagerServerGameState.
        if (event instanceof AddClientEvent) {
            AddClientEvent addClientEvent = (AddClientEvent) event;
            ClientData clientData = addClientEvent.getClientData();
            GroupDataModel group = addClientEvent.getGroup();
            group.setServerDataModel(this);
            addClientToGroup(clientData, group);
            // XXX: this must occur after we add the client to the group because addClientToGroup() sets
            // the position according to the spacing algorithm.
            clientData.setPosition(addClientEvent.getPosition());
        }
        else if (event instanceof ResourcesAddedEvent) {
            ResourcesAddedEvent resourcesAddedEvent = (ResourcesAddedEvent) event;
            addResources(resourcesAddedEvent.getGroup(), resourcesAddedEvent.getResources());
            setDirty(true);
        }
        else if (event instanceof MovementEvent) {
            MovementEvent movementEvent = (MovementEvent) event;
            moveClient(movementEvent.getId(), movementEvent.getDirection());
            setDirty(true);
        }
        else if (event instanceof ResourceAddedEvent) {
            ResourceAddedEvent resourceAddedEvent = (ResourceAddedEvent) event;
            addResource(resourceAddedEvent.getGroup(), resourceAddedEvent.getResource());
            setDirty(true);
        } 
        else if (event instanceof RealTimeSanctionRequest) {
            // currently unhandled.
        	setDirty(true);
        }
        else if (event instanceof ResetTokenDistributionRequest) {
            getGroup(event.getId()).resetResourceDistribution();
            setDirty(true);
        }
        else if (event instanceof TokenCollectedEvent) {
            TokenCollectedEvent tokenCollectedEvent = (TokenCollectedEvent) event;
            getGroup(event.getId()).removeResource(tokenCollectedEvent.getLocation());
            setDirty(true);
        }
        else if (event instanceof ExplicitCollectionModeRequest) {
            ExplicitCollectionModeRequest request = (ExplicitCollectionModeRequest) event;
            getClientData(request.getId()).setExplicitCollectionMode(request.isExplicitCollectionMode());
        }
        else {
            logger.warning("unapplied event:" + event);
        }
    }

    public synchronized void removeClient(Identifier id) {
        GroupDataModel groupDataModel = clientsToGroups.remove(id);
        if (groupDataModel != null) {
        	groupDataModel.removeClient(id);
        }
    }

    public synchronized void addClient(ClientData clientData) {
        // iterate through all existing groups and try to add to them.
        for (GroupDataModel group : getGroups()) {
            if (group.isFull()) {
                continue;
            }
            addClientToGroup(clientData, group);
            return;
        }
        // all the groups are full, create a new one and add them
        GroupDataModel group = new GroupDataModel(this);
        addClientToGroup(clientData, group);
    }

    public synchronized void addClientToGroup(ClientData clientData, GroupDataModel group) {
        group.addClient(clientData);
        clientsToGroups.put(clientData.getId(), group);

        // Assign chat handle
        String chatHandle = getRoundConfiguration().getChatHandlePrefix() + (
                getRoundConfiguration().areChatHandlesNumeric() ? group.size() : RoundConfiguration.CHAT_HANDLES[group.size() - 1]);
        clientData.getId().setChatHandle(chatHandle);

        channel.handle(new AddClientEvent(clientData, group, clientData.getPosition()));
    }

    public void addResource(GroupDataModel group, Resource resource) {
        group.addResource(resource);
        channel.handle(new ResourceAddedEvent(group, resource));
    }

    public void moveResources(GroupDataModel group, Collection<Point> removedPoints, Collection<Point> addedPoints) {
        // first remove all resources
        group.moveResources(removedPoints, addedPoints);
        channel.handle(new TokensMovedEvent(removedPoints, addedPoints));
    }

    public void addResources(GroupDataModel group, Set<Resource> resources) {
        group.addResources(resources);
        channel.handle(new ResourcesAddedEvent(group, resources));
    }

    public void moveResource(GroupDataModel group, Point oldLocation, Point newLocation) {
        group.addResource(newLocation);
        group.removeResource(oldLocation);
        channel.handle(new TokenMovedEvent(oldLocation, newLocation));
    }

    public void cleanupRound() {
        for (GroupDataModel group: clientsToGroups.values()) {
            group.cleanupRound();
        }
    }

    public Set<Point> getResourcePositions(Identifier id) {
        return clientsToGroups.get(id).getResourcePositions();
    }

    public Point createRandomPoint() {
        int x = random.nextInt(getBoardWidth());
        int y = random.nextInt(getBoardHeight());
        return new Point(x, y);
    }

    public void clear() {
        // XXX: we no longer remove the Groups from the ServerGameState since we want persistent groups.
        // This should be configurable?
        for (Iterator<GroupDataModel> iter = clientsToGroups.values().iterator(); iter.hasNext(); ) {
            GroupDataModel group = iter.next();
            group.clear();
            iter.remove();
        }
    }

    public Map<Identifier, ClientData> getClientDataMap() {
        Map<Identifier, ClientData> clientDataMap = new HashMap<>();
        for (Map.Entry<Identifier, GroupDataModel> entry : clientsToGroups.entrySet()) {
            Identifier id = entry.getKey();
            GroupDataModel group = entry.getValue();
            clientDataMap.put(id, group.getClientData(id));
        }
        return clientDataMap;
    }

    public Map<Identifier, Bot> getBotMap() {
        Map<Identifier, Bot> botMap = new HashMap<>();
        for (GroupDataModel group: getGroups()) {
            botMap.putAll(group.getBotMap());
        }
        return botMap;
    }

    public Map<Identifier, Actor> getActorMap() {
        Map<Identifier, Actor> actorMap = new HashMap<>();
        for (GroupDataModel group: getGroups()) {
            actorMap.putAll(group.getClientDataMap());
            actorMap.putAll(group.getBotMap());
        }
        return actorMap;
    }

    public void moveClient(Identifier id, Direction d) {
        getGroup(id).moveClient(id, d);
        channel.handle(new MovementEvent(id, d));
    }

    public Point getClientPosition(Identifier id) {
        GroupDataModel group = clientsToGroups.get(id);
        return group.getClientPosition(id);
    }

    public int getNumberOfClients() {
        return clientsToGroups.keySet().size();
    }
    
    public int getNumberOfGroups() {
    	return getGroups().size();
    }

    public Set<GroupDataModel> getGroups() {
        return new LinkedHashSet<GroupDataModel>(clientsToGroups.values());
    }
    
    public List<GroupDataModel> getOrderedGroups() {
        return new ArrayList<GroupDataModel>(new TreeSet<GroupDataModel>(clientsToGroups.values()));
    }

    public ClientData getClientData(Identifier id) {
        return getGroup(id).getClientData(id);
    }

    public GroupDataModel getGroup(Identifier id) {
        GroupDataModel group = clientsToGroups.get(id);
        if (group == null) {
            if (id instanceof BotIdentifier) {
                return getBotMap().get(id).getGroupDataModel();
            }
            logger.warning("No group available for id:" + id);
        }
        return group;
    }

    /**
     * Returns a Map<Identifier, Point> representing the latest client
     * positions.
     */
    public Map<Identifier, Point> getClientPositions(Identifier clientId) {
        GroupDataModel group = clientsToGroups.get(clientId);
        if (group == null) {
            throw new IllegalArgumentException("No group assigned to client id: " + clientId);
        }
        return group.getClientPositions();
    }

    public int getTokensConsumedBy(Identifier id) {
        return clientsToGroups.get(id).getCurrentTokens(id);
    }



    public boolean lockResource(LockResourceRequest event) {
        // lock resource
        System.err.println("Modifying lock status for resource: " + event.getResource() + " from station: " + event.getId());
        return clientsToGroups.get(event.getId()).lockResource(event);
    }

    public void unlockResource(UnlockResourceRequest event) {
        clientsToGroups.get(event.getId()).unlockResource(event);
    }

    public void harvestResource(HarvestResourceRequest event) {
        // harvest resource
        Identifier id = event.getId();
        GroupDataModel group = clientsToGroups.get(id);
        group.harvestResource(id, event.getResource());
    }

    public void harvestFruits(HarvestFruitRequest event) {
        Identifier id = event.getId();
        GroupDataModel group = clientsToGroups.get(id);
        group.harvestFruits(id, event.getResource());
    }

    public Queue<RealTimeSanctionRequest> getLatestSanctions(Identifier id) {
        return clientsToGroups.get(id).getClientData(id).getLatestSanctions();
    }

    public void resetSanctionCount(Identifier id) {
        clientsToGroups.get(id).getClientData(id).resetLatestSanctions();
    }

    public Map<Identifier, ClientData> getClientDataMap(Identifier clientId) {
        GroupDataModel group = clientsToGroups.get(clientId);
        return group.getClientDataMap();
    }

    public void setGroups(Collection<GroupDataModel> groups) {
        for (GroupDataModel group: groups) {
            group.setServerDataModel(this);

            for (Identifier id: group.getClientIdentifiers()) {
                clientsToGroups.put(id, group);
            }
        }
    }

    public void setNullEventChannel() {
        super.channel = new EventTypeChannel() {
            public void handle(Event event) { }
        };
    }

    public void resetGroupResourceDistributions() {
        for (GroupDataModel group: getGroups()) {
            group.resetResourceDistribution();
        }
    }
    /**
     * Reinitializes this server data model in preparation for a replay:
     * <ol>
     * <li> Sets event channel to a no-op event channel.</li>
     * <li> resets all group resource distributions </li>
     * <li> reinitializes all client positions </li>
     * </ol>
     * FIXME: may be safer to return a clone() instead?
     */
    public void reinitialize(RoundConfiguration roundConfiguration) {
        setRoundConfiguration(roundConfiguration);
        setNullEventChannel();
        resetGroupResourceDistributions();
        // initialize all client positions
        for (GroupDataModel group: getGroups()) {
            for (ClientData clientData: group.getClientDataMap().values()) {
                clientData.initializePosition();
                if (! clientData.getGroupDataModel().equals(group)) {
                    logger.warning("client data model had different group " + clientData.getGroupDataModel() + " than server's group: " + group);
                    clientData.setGroupDataModel(group);
                }
            }
        }
    }

    public boolean isLastRound() {
        return getRoundConfiguration().isLastRound();
    }

    private void readObject(ObjectInputStream objectInputStream) throws IOException, ClassNotFoundException {
        objectInputStream.defaultReadObject();
        for (GroupDataModel group: getGroups()) {
            group.setServerDataModel(this);
            // should we clear the resource distribution for all groups as well?  However, this means we won't be able to get the residual token counts
            // from the  
            // group.resetResourceDistribution();
        }
        super.channel = new EventTypeChannel();
        logger = Logger.getLogger( getClass().getName() );
        random = new Random();
    }

    public void unapply(PersistableEvent persistableEvent) {
        logger.warning("unapply() not implemented yet: " + persistableEvent);
    }

    public TrustGameResult calculateTrustGame(ClientData playerOne, ClientData playerTwo) {
    	TrustGameResult result = new TrustGameResult(playerOne, playerTwo);
        if (playerOne.getId().equals(playerTwo.getId())) {
        	String errorMessage = playerOne + " tried to calculate trust game with self, aborting";
        	logger.warning(errorMessage);
        	result.setLog(errorMessage);
        	return result;
        }
        double playerOneAmountToKeep = playerOne.getTrustGamePlayerOneAmountToKeep();
        result.setPlayerOneAmountToKeep(playerOneAmountToKeep);
        double[] playerTwoAmountsToKeep = playerTwo.getTrustGamePlayerTwoAmountsToKeep();
        result.setPlayerTwoAmountsToKeep(playerTwoAmountsToKeep);
        double amountSent = 1.0d - playerOneAmountToKeep;

        double playerOneEarnings = playerOneAmountToKeep;
        double playerTwoEarnings = 0.0d;
        double amountReturnedToPlayerOne = 0.0d;
        double totalAmountSent = amountSent * 3.0d;
        if (amountSent > 0) {
            int index = 0;
            if (amountSent == 0.25d) {
                index = 0;
            } else if (amountSent == 0.50d) {
                index = 1;
            } else if (amountSent == 0.75d) {
                index = 2;
            } else if (amountSent == 1.0d) {
                index = 3;
            }
            playerTwoEarnings = playerTwoAmountsToKeep[index];
            amountReturnedToPlayerOne = totalAmountSent - playerTwoEarnings;
            playerOneEarnings += amountReturnedToPlayerOne;
        }
        StringBuilder builder = new StringBuilder();
        String playerOneLog = String.format(" Player 1 kept %s, sent %s, and received %s back from Player 2 for a total earnings of %s", 
        		CURRENCY_FORMATTER.format(playerOneAmountToKeep),
        		CURRENCY_FORMATTER.format(amountSent),
        		CURRENCY_FORMATTER.format(amountReturnedToPlayerOne), 
        		CURRENCY_FORMATTER.format(playerOneEarnings));
        playerOne.logTrustGame("You were Player 1." + playerOneLog);
        playerOne.addTrustGameEarnings(playerOneEarnings);
        builder.append(playerOne).append(playerOneLog).append("\n");
        if (shouldLogPlayerTwo(playerOne, playerTwo)) {
        	String playerTwoLog = String.format(" Player 2 received %s from Player 1 and sent back %s for a total earnings of %s", 
        			CURRENCY_FORMATTER.format(totalAmountSent),
        			CURRENCY_FORMATTER.format(totalAmountSent - playerTwoEarnings),
        			CURRENCY_FORMATTER.format(playerTwoEarnings));
        	playerTwo.logTrustGame("You were Player 2." + playerTwoLog);
        	playerTwo.addTrustGameEarnings(playerTwoEarnings);
        	builder.append(playerTwo).append(playerTwoLog).append("\n");
        }
        else {
        	builder.append(
        			String.format("%s already participated in the trust game and was only used as a Player 2 strategy to respond to Player 1.", 
        					playerTwo));
        }
        result.setPlayerOneEarnings(playerOneEarnings);
        result.setPlayerTwoEarnings(playerTwoEarnings);
        result.setLog(builder.toString());
        return result;
    }
    
    /**
     * Returns true if player two has not yet participated in the trust game as a player one, in which case their trust game log
     * would already have an entry and so its size would be equivalent to the size of the player one log.  In most cases it should 
     * always be 1 smaller than the player one trust game log
     * 
     * @param playerOne
     * @param playerTwo
     * @return
     */
    private boolean shouldLogPlayerTwo(ClientData playerOne, ClientData playerTwo) {
    	logger.info(String.format("%s (P1) log: %s vs. %s (P2) log: %s", playerOne, playerOne.getTrustGameLog(), 
    			playerTwo, playerTwo.getTrustGameLog()));
    	// is this sufficient? 
    	return playerTwo.getTrustGameLog().size() < playerOne.getTrustGameLog().size();
    }

    @Override
    public List<Identifier> getAllClientIdentifiers() {
        return new ArrayList<Identifier>(clientsToGroups.keySet());

    }
    

    public List<GroupDataModel> allocateImposedStrategyDistribution(Map<Strategy, Integer> imposedStrategyDistribution) {
        if (imposedStrategyDistribution == null || imposedStrategyDistribution.isEmpty()) {
            throw new IllegalArgumentException("No strategy distribution defined.  Please create a strategy distribution and try again.");
        }
        List<GroupDataModel> groups = getOrderedGroups();
        int numberOfGroups = groups.size();
        Collections.shuffle(groups);
        Iterator<GroupDataModel> groupIterator = groups.iterator();
        int numberOfStrategies = 0;
        for (Map.Entry<Strategy, Integer> entry : imposedStrategyDistribution.entrySet()) {
            Strategy strategy = entry.getKey();
            int occurrences = entry.getValue();
            if (numberOfStrategies > numberOfGroups) {
                throw new IllegalArgumentException("Invalid number of strategies : " + numberOfStrategies + " for " + numberOfGroups + " groups.");
            }
            for (int i = 0; i < occurrences; i++) {
                GroupDataModel group = groupIterator.next();
                group.setImposedStrategy(strategy);
                numberOfStrategies++;
            }
        }
        return groups;
    }
    
    public List<GroupDataModel> allocateImposedStrategyDistribution() {
        return allocateImposedStrategyDistribution(imposedStrategyDistribution);
    }

    public void setImposedStrategyDistribution(Map<Strategy, Integer> strategyDistribution) {
        this.imposedStrategyDistribution = strategyDistribution;              
    }

    public Map<Strategy, Integer> getImposedStrategyDistribution() {
        return imposedStrategyDistribution;
    }

    public synchronized void handleTokenCollectionRequest(ClientData clientData) {
        GroupDataModel group = getGroup(clientData.getId());
        group.collectToken(clientData);
    }

}
