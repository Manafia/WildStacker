package com.bgsoftware.wildstacker.handlers;

import com.bgsoftware.wildstacker.Locale;
import com.bgsoftware.wildstacker.WildStackerPlugin;
import com.bgsoftware.wildstacker.api.enums.SpawnCause;
import com.bgsoftware.wildstacker.api.handlers.SystemManager;
import com.bgsoftware.wildstacker.api.loot.LootTable;
import com.bgsoftware.wildstacker.api.objects.StackedBarrel;
import com.bgsoftware.wildstacker.api.objects.StackedEntity;
import com.bgsoftware.wildstacker.api.objects.StackedItem;
import com.bgsoftware.wildstacker.api.objects.StackedObject;
import com.bgsoftware.wildstacker.api.objects.StackedSnapshot;
import com.bgsoftware.wildstacker.api.objects.StackedSpawner;
import com.bgsoftware.wildstacker.database.Query;
import com.bgsoftware.wildstacker.database.SQLHelper;
import com.bgsoftware.wildstacker.database.StatementHolder;
import com.bgsoftware.wildstacker.listeners.EntitiesListener;
import com.bgsoftware.wildstacker.objects.WStackedBarrel;
import com.bgsoftware.wildstacker.objects.WStackedEntity;
import com.bgsoftware.wildstacker.objects.WStackedItem;
import com.bgsoftware.wildstacker.objects.WStackedSnapshot;
import com.bgsoftware.wildstacker.objects.WStackedSpawner;
import com.bgsoftware.wildstacker.tasks.ItemsMerger;
import com.bgsoftware.wildstacker.tasks.KillTask;
import com.bgsoftware.wildstacker.tasks.StackTask;
import com.bgsoftware.wildstacker.utils.GeneralUtils;
import com.bgsoftware.wildstacker.utils.chunks.ChunkPosition;
import com.bgsoftware.wildstacker.utils.entity.EntityStorage;
import com.bgsoftware.wildstacker.utils.entity.EntityUtils;
import com.bgsoftware.wildstacker.utils.items.ItemUtils;
import com.bgsoftware.wildstacker.utils.legacy.Materials;
import com.bgsoftware.wildstacker.utils.pair.Pair;
import com.bgsoftware.wildstacker.utils.threads.Executor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class SystemHandler implements SystemManager {

    private final WildStackerPlugin plugin;
    private final DataHandler dataHandler;

    public SystemHandler(WildStackerPlugin plugin){
        this.plugin = plugin;
        this.dataHandler = plugin.getDataHandler();

        //Start all required tasks
        Executor.sync(() -> {
            KillTask.start();
            StackTask.start();
            ItemsMerger.start();
        }, 1L);

        //Start the auto-clear
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::performCacheClear, 100L, 100L);
        //Start the auto-save
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> Executor.data(this::performCacheSave), 6000L, 6000L);
    }

    /*
     * StackedObject's methods
     */

    @Override
    public void removeStackObject(StackedObject stackedObject) {
        if(stackedObject instanceof StackedEntity)
            dataHandler.CACHED_ENTITIES.remove(((StackedEntity) stackedObject).getUniqueId());
        else if(stackedObject instanceof StackedItem)
            dataHandler.CACHED_ITEMS.remove(((StackedItem) stackedObject).getUniqueId());
        else if(stackedObject instanceof StackedSpawner)
            dataHandler.removeStackedSpawner((StackedSpawner) stackedObject);
        else if(stackedObject instanceof StackedBarrel)
            dataHandler.removeStackedBarrel((StackedBarrel) stackedObject);
    }

    @Override
    public StackedEntity getStackedEntity(LivingEntity livingEntity) {
        StackedEntity stackedEntity = dataHandler.CACHED_ENTITIES.get(livingEntity.getUniqueId());

        if(stackedEntity != null && stackedEntity.getLivingEntity() != null)
            return stackedEntity;

        //Entity wasn't found, creating a new object
        if(EntityStorage.hasMetadata(livingEntity, "spawn-cause"))
            stackedEntity = new WStackedEntity(livingEntity, 1, EntityStorage.getMetadata(livingEntity, "spawn-cause", SpawnCause.class));
        else
            stackedEntity = new WStackedEntity(livingEntity);

        //Checks if the entity still exists after a few ticks
        Executor.sync(() -> {
            if(livingEntity.isDead())
                dataHandler.CACHED_ENTITIES.remove(livingEntity.getUniqueId());
        }, 10L);

        boolean shouldBeCached = ((WStackedEntity) stackedEntity).isCached();

        //A new entity was created. Let's see if we need to add him
        if(!(livingEntity instanceof Player) && !livingEntity.getType().name().equals("ARMOR_STAND") && shouldBeCached)
            dataHandler.CACHED_ENTITIES.put(stackedEntity.getUniqueId(), stackedEntity);

        Pair<Integer, SpawnCause> entityData = shouldBeCached ? dataHandler.CACHED_AMOUNT_ENTITIES.remove(livingEntity.getUniqueId()) :
                dataHandler.CACHED_AMOUNT_ENTITIES.get(livingEntity.getUniqueId());

        if(entityData != null){
            stackedEntity.setStackAmount(entityData.getKey(), true);
            stackedEntity.setSpawnCause(entityData.getValue());
        }

        boolean deadFlag = shouldBeCached ? dataHandler.CACHED_DEAD_ENTITIES.remove(livingEntity.getUniqueId()) :
                dataHandler.CACHED_DEAD_ENTITIES.contains(livingEntity.getUniqueId());

        if(deadFlag)
            ((WStackedEntity) stackedEntity).setDeadFlag(true);

        return stackedEntity;
    }

    @Override
    public StackedItem getStackedItem(Item item) {
        StackedItem stackedItem = dataHandler.CACHED_ITEMS.get(item.getUniqueId());

        if(stackedItem != null && stackedItem.getItem() != null)
            return stackedItem;

        //Item wasn't found, creating a new object.
        stackedItem = new WStackedItem(item);

        //Checks if the item still exists after a few ticks
        Executor.sync(() -> {
            if(item.isDead())
                dataHandler.CACHED_ITEMS.remove(item.getUniqueId());
        }, 10L);

        //A new item was created. Let's see if we need to add him
        if(plugin.getSettings().itemsStackingEnabled && stackedItem.isWhitelisted() && !stackedItem.isBlacklisted() && !stackedItem.isWorldDisabled())
            dataHandler.CACHED_ITEMS.put(stackedItem.getUniqueId(), stackedItem);

        Integer stackAmount = dataHandler.CACHED_AMOUNT_ITEMS.remove(item.getUniqueId());

        if(stackAmount != null)
            stackedItem.setStackAmount(stackAmount, true);

        return stackedItem;
    }

    public void loadSpawners(Chunk chunk){
        Iterator<Map.Entry<Location, Integer>> spawnersIterator =
                dataHandler.CACHED_AMOUNT_SPAWNERS.entrySet().iterator();

        while(spawnersIterator.hasNext()){
            Map.Entry<Location, Integer> entry = spawnersIterator.next();
            if(GeneralUtils.isSameChunk(entry.getKey(), chunk)){
                Block block = entry.getKey().getBlock();

                if(block.getType() == Materials.SPAWNER.toBukkitType()){
                    StackedSpawner stackedSpawner = new WStackedSpawner((CreatureSpawner) block.getState());
                    stackedSpawner.setStackAmount(entry.getValue(), true);
                    dataHandler.addStackedSpawner(stackedSpawner);
                }

                spawnersIterator.remove();
            }
        }
    }


    @Override
    public StackedSpawner getStackedSpawner(CreatureSpawner spawner) {
        return getStackedSpawner(spawner.getLocation());
    }

    @Override
    public StackedSpawner getStackedSpawner(Location location) {
        StackedSpawner stackedSpawner = dataHandler.CACHED_SPAWNERS.get(location);

        if(stackedSpawner != null)
            return stackedSpawner;

        //Spawner wasn't found, creating a new object
        stackedSpawner = new WStackedSpawner((CreatureSpawner) location.getBlock().getState());

        StackedSpawner STACKED_SPAWNER = stackedSpawner;

        //Checks if the spawner still exists after a few ticks
        Executor.sync(() -> {
            if(!isStackedSpawner(location.getBlock()))
                dataHandler.removeStackedSpawner(STACKED_SPAWNER);
        }, 10L);

        //A new spawner was created. Let's see if we need to add him
        if(plugin.getSettings().spawnersStackingEnabled && stackedSpawner.isWhitelisted() && !stackedSpawner.isBlacklisted() && !stackedSpawner.isWorldDisabled())
            dataHandler.addStackedSpawner(stackedSpawner);

        return stackedSpawner;
    }

    public void loadBarrels(Chunk chunk){
        Iterator<Map.Entry<Location, Pair<Integer, ItemStack>>> barrelsIterator =
                dataHandler.CACHED_AMOUNT_BARRELS.entrySet().iterator();

        while(barrelsIterator.hasNext()){
            Map.Entry<Location, Pair<Integer, ItemStack>> entry = barrelsIterator.next();
            if(GeneralUtils.isSameChunk(entry.getKey(), chunk)){
                Block block = entry.getKey().getBlock();

                if(block.getType() == Material.CAULDRON){
                    StackedBarrel stackedBarrel = new WStackedBarrel(block, entry.getValue().getValue());
                    stackedBarrel.setStackAmount(entry.getValue().getKey(), true);
                    stackedBarrel.createDisplayBlock();
                    dataHandler.addStackedBarrel(stackedBarrel);
                }

                barrelsIterator.remove();
            }
        }
    }
    @Override
    public StackedBarrel getStackedBarrel(Block block) {
        return getStackedBarrel(block == null ? null : block.getLocation());
    }

    @Override
    public StackedBarrel getStackedBarrel(Location location) {
        StackedBarrel stackedBarrel = dataHandler.CACHED_BARRELS.get(location);

        if(stackedBarrel != null)
            return stackedBarrel;

        //Barrel wasn't found, creating a new object
        stackedBarrel = new WStackedBarrel(location.getBlock(), ItemUtils.getFromBlock(location.getBlock()));

        //Checks if the barrel still exists after a few ticks
        Executor.sync(() -> {
            if(isStackedBarrel(location.getBlock()))
                WStackedBarrel.of(location.getBlock()).createDisplayBlock();
        }, 2L);

        StackedBarrel STACKED_BARREL = stackedBarrel;

        //Checks if the barrel still exists after a few ticks
        Executor.sync(() -> {
            if(!isStackedBarrel(location.getBlock()))
                dataHandler.removeStackedBarrel(STACKED_BARREL);
        }, 10L);

        //A new barrel was created. Let's see if we need to add him
        if(plugin.getSettings().barrelsStackingEnabled && stackedBarrel.isWhitelisted() && !stackedBarrel.isBlacklisted() && !stackedBarrel.isWorldDisabled())
            dataHandler.addStackedBarrel(stackedBarrel);

        return stackedBarrel;
    }

    @Override
    public List<StackedEntity> getStackedEntities() {
        return new ArrayList<>(dataHandler.CACHED_ENTITIES.values());
    }

    @Override
    public List<StackedItem> getStackedItems() {
        return new ArrayList<>(dataHandler.CACHED_ITEMS.values());
    }

    @Override
    public List<StackedSpawner> getStackedSpawners(){
        return new ArrayList<>(dataHandler.CACHED_SPAWNERS.values());
    }

    public List<StackedSpawner> getStackedSpawners(Chunk chunk) {
        Set<StackedSpawner> chunkSpawners = dataHandler.CACHED_SPAWNERS_BY_CHUNKS.get(new ChunkPosition(chunk));
        return chunkSpawners == null ? new ArrayList<>() : new ArrayList<>(chunkSpawners);
    }

    @Override
    public List<StackedBarrel> getStackedBarrels(){
        return new ArrayList<>(dataHandler.CACHED_BARRELS.values());
    }

    public List<StackedBarrel> getStackedBarrels(Chunk chunk) {
        Set<StackedBarrel> chunkBarrels = dataHandler.CACHED_BARRELS_BY_CHUNKS.get(new ChunkPosition(chunk));
        return chunkBarrels == null ? new ArrayList<>() : new ArrayList<>(chunkBarrels);
    }

    @Override
    public boolean isStackedSpawner(Block block) {
        return block != null && block.getType() == Materials.SPAWNER.toBukkitType() && dataHandler.CACHED_SPAWNERS.containsKey(block.getLocation());
    }

    @Override
    public boolean isStackedBarrel(Block block) {
        return block != null && block.getType() == Material.CAULDRON && dataHandler.CACHED_BARRELS.containsKey(block.getLocation());
    }

    @Override
    public void performCacheClear() {
        List<StackedObject> stackedObjects = dataHandler.getStackedObjects();

        for(StackedObject stackedObject : stackedObjects){
            if(stackedObject instanceof StackedItem){
                StackedItem stackedItem = (StackedItem) stackedObject;
                if(stackedItem.getItem() == null || (GeneralUtils.isChunkLoaded(stackedItem.getItem().getLocation()) && stackedItem.getItem().isDead()))
                    removeStackObject(stackedObject);
            }

            else if(stackedObject instanceof StackedEntity){
                StackedEntity stackedEntity = (StackedEntity) stackedObject;
                if(stackedEntity.getLivingEntity() == null || (GeneralUtils.isChunkLoaded(stackedEntity.getLivingEntity().getLocation()) &&
                        stackedEntity.getLivingEntity().isDead() && !((WStackedEntity) stackedEntity).hasDeadFlag()))
                    removeStackObject(stackedObject);
                else
                    stackedEntity.updateNerfed();
            }

            else if(stackedObject instanceof StackedSpawner){
                StackedSpawner stackedSpawner = (StackedSpawner) stackedObject;
                if(GeneralUtils.isChunkLoaded(stackedSpawner.getLocation()) && !isStackedSpawner(stackedSpawner.getSpawner().getBlock())) {
                    removeStackObject(stackedObject);
                }
            }

            else if(stackedObject instanceof StackedBarrel){
                StackedBarrel stackedBarrel = (StackedBarrel) stackedObject;
                if(GeneralUtils.isChunkLoaded(stackedBarrel.getLocation()) && !isStackedBarrel(stackedBarrel.getBlock())) {
                    removeStackObject(stackedObject);
                    stackedBarrel.removeDisplayBlock();
                }
            }
        }

        Executor.sync(plugin.getProviders()::clearHolograms);
    }

    @Override
    public void performCacheSave() {
        SQLHelper.executeUpdate("DELETE FROM entities;");

        if(plugin.getSettings().storeEntities) {
            Map<UUID, Pair<Integer, SpawnCause>> entityAmounts = new HashMap<>();

            getStackedEntities().forEach(stackedEntity -> {
                if (stackedEntity.getStackAmount() > 1 || hasValidSpawnCause(stackedEntity.getSpawnCause())) {
                    entityAmounts.put(stackedEntity.getUniqueId(), new Pair<>(stackedEntity.getStackAmount(), stackedEntity.getSpawnCause()));
                } else {
                    removeStackObject(stackedEntity);
                }
            });

            new HashMap<>(dataHandler.CACHED_AMOUNT_ENTITIES).forEach((uuid, pair) -> {
                if (pair.getKey() > 1 || hasValidSpawnCause(pair.getValue())) {
                    entityAmounts.put(uuid, new Pair<>(Math.max(entityAmounts.getOrDefault(uuid, new Pair<>(1, null)).getKey(), pair.getKey()), pair.getValue()));
                } else {
                    dataHandler.CACHED_AMOUNT_ENTITIES.remove(uuid);
                }
            });

            if(entityAmounts.size() > 0) {
                StatementHolder entityHolder = Query.ENTITY_INSERT.getStatementHolder();
                entityAmounts.forEach((uuid, pair) ->
                    entityHolder.setString(uuid.toString()).setInt(pair.getKey()).setString(pair.getValue().name()).addBatch());
                entityHolder.execute(false);
            }
        }

        SQLHelper.executeUpdate("DELETE FROM items;");

        if(plugin.getSettings().storeItems) {
            Map<UUID, Integer> itemAmounts = new HashMap<>();

            getStackedItems().forEach(stackedItem -> {
                if (stackedItem.getStackAmount() > 1) {
                    itemAmounts.put(stackedItem.getUniqueId(), stackedItem.getStackAmount());
                } else {
                    removeStackObject(stackedItem);
                }
            });

            new HashMap<>(dataHandler.CACHED_AMOUNT_ITEMS).forEach((uuid, stackAmount) -> {
                if (stackAmount > 1) {
                    itemAmounts.put(uuid, Math.max(itemAmounts.getOrDefault(uuid, 1), stackAmount));
                } else {
                    dataHandler.CACHED_AMOUNT_ITEMS.remove(uuid);
                }
            });

            if(itemAmounts.size() > 0) {
                StatementHolder itemHolder = Query.ITEM_INSERT.getStatementHolder();
                itemAmounts.forEach((uuid, stackAmount) ->
                        itemHolder.setString(uuid.toString()).setInt(stackAmount).addBatch());
                itemHolder.execute(false);
            }
        }

        SQLHelper.executeUpdate("DELETE FROM spawners;");

        {
            Map<Location, Integer> spawnerAmounts = new HashMap<>();

            getStackedSpawners().forEach(stackedSpawner -> {
                if (stackedSpawner.getStackAmount() > 1) {
                    spawnerAmounts.put(stackedSpawner.getLocation(), stackedSpawner.getStackAmount());
                } else {
                    removeStackObject(stackedSpawner);
                }
            });

            new HashMap<>(dataHandler.CACHED_AMOUNT_SPAWNERS).forEach((location, stackAmount) -> {
                if (stackAmount > 1) {
                    spawnerAmounts.put(location, Math.max(spawnerAmounts.getOrDefault(location, 1), stackAmount));
                } else {
                    dataHandler.CACHED_AMOUNT_SPAWNERS.remove(location);
                }
            });

            if(spawnerAmounts.size() > 0) {
                StatementHolder spawnersHolder = Query.SPAWNER_INSERT.getStatementHolder();
                spawnerAmounts.forEach((location, stackAmount) ->
                        spawnersHolder.setLocation(location).setInt(stackAmount).addBatch());
                spawnersHolder.execute(false);
            }
        }

        SQLHelper.executeUpdate("DELETE FROM barrels;");

        {
            Map<Location, Pair<Integer, ItemStack>> barrelAmounts = new HashMap<>();

            getStackedBarrels().forEach(stackedBarrel ->
                barrelAmounts.put(stackedBarrel.getLocation(),
                        new Pair<>(stackedBarrel.getStackAmount(), stackedBarrel.getBarrelItem(1))));

            new HashMap<>(dataHandler.CACHED_AMOUNT_BARRELS).forEach((location, pair) ->
                barrelAmounts.put(location, new Pair<>(Math.max(barrelAmounts.getOrDefault(location, new Pair<>(1, null)).getKey(), pair.getKey()), pair.getValue())));

            if(barrelAmounts.size() > 0) {
                StatementHolder barrelsHolder = Query.BARREL_INSERT.getStatementHolder();
                barrelAmounts.forEach((location, pair) ->
                        barrelsHolder.setLocation(location).setInt(pair.getKey()).setItemStack(pair.getValue()).addBatch());
                barrelsHolder.execute(false);
            }
        }
    }

    private boolean hasValidSpawnCause(SpawnCause spawnCause){
        return spawnCause != SpawnCause.CHUNK_GEN && spawnCause != SpawnCause.NATURAL;
    }

    @Override
    public void updateLinkedEntity(LivingEntity livingEntity, LivingEntity newLivingEntity) {
        for(StackedSpawner stackedSpawner : getStackedSpawners()){
            LivingEntity linkedEntity = ((WStackedSpawner) stackedSpawner).getRawLinkedEntity();
            if(linkedEntity != null && linkedEntity.equals(livingEntity))
                stackedSpawner.setLinkedEntity(newLivingEntity);
        }
    }

    /*
     * General methods
     */

    @Override
    public <T extends Entity> T spawnEntityWithoutStacking(Location location, Class<T> type){
        return spawnEntityWithoutStacking(location, type, SpawnCause.SPAWNER);
    }

    @Override
    public <T extends Entity> T spawnEntityWithoutStacking(Location location, Class<T> type, CreatureSpawnEvent.SpawnReason spawnReason) {
        return spawnEntityWithoutStacking(location, type, SpawnCause.valueOf(spawnReason));
    }

    @Override
    public <T extends Entity> T spawnEntityWithoutStacking(Location location, Class<T> type, SpawnCause spawnCause) {
        return plugin.getNMSAdapter().createEntity(location, type, spawnCause,
                entity -> EntitiesListener.noStackEntities.add(entity.getUniqueId()));
    }

    @Override
    public StackedItem spawnItemWithAmount(Location location, ItemStack itemStack) {
        return spawnItemWithAmount(location, itemStack, itemStack.getAmount());
    }

    @Override
    public StackedItem spawnItemWithAmount(Location location, ItemStack itemStack, int amount) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        location = location.clone();

        location.setX(location.getX() + (random.nextFloat() * 0.5F) + 0.25D);
        location.setY(location.getY() + (random.nextFloat() * 0.5F) + 0.25D);
        location.setZ(location.getZ() + (random.nextFloat() * 0.5F) + 0.25D);

        int limit = plugin.getSettings().itemsLimits.getOrDefault(itemStack, Integer.MAX_VALUE);
        limit = limit < 1 ? Integer.MAX_VALUE : limit;

        int amountOfItems = amount / limit;

        int itemLimit = limit;

        for(int i = 0; i < amountOfItems; i++) {
            itemStack = itemStack.clone();
            itemStack.setAmount(Math.min(itemStack.getMaxStackSize(), itemLimit));
            WStackedItem.of(plugin.getNMSAdapter().createItem(location, itemStack, SpawnCause.CUSTOM, item -> {
                StackedItem stackedItem = WStackedItem.of(item);
                stackedItem.setStackAmount(itemLimit, !stackedItem.isBlacklisted() && !stackedItem.isWhitelisted() && !stackedItem.isWorldDisabled());
            }));
        }

        int leftOvers = amount % limit;
        itemStack = itemStack.clone();
        itemStack.setAmount(Math.min(itemStack.getMaxStackSize(), leftOvers));
        return WStackedItem.of(plugin.getNMSAdapter().createItem(location, itemStack, SpawnCause.CUSTOM, item -> {
            StackedItem stackedItem = WStackedItem.of(item);
            stackedItem.setStackAmount(leftOvers, !stackedItem.isBlacklisted() && !stackedItem.isWhitelisted() && !stackedItem.isWorldDisabled());
        }));
    }

    @Override
    public void spawnCorpse(StackedEntity stackedEntity) {
        Class<? extends Entity> entityClass = stackedEntity.getType().getEntityClass();

        if(entityClass == null)
            return;

        LivingEntity livingEntity = (LivingEntity) plugin.getNMSAdapter().createEntity(stackedEntity.getLivingEntity().getLocation(),
                entityClass, SpawnCause.CUSTOM, entity -> {
            LivingEntity spawnedEntity = (LivingEntity) entity;
            EntitiesListener.noStackEntities.add(spawnedEntity.getUniqueId());
            //EntityData.of(stackedEntity).applyEntityData(spawnedEntity);
            EntityStorage.setMetadata(spawnedEntity, "corpse", null);
        });

        if(livingEntity != null){
            if(livingEntity instanceof Slime){
                ((Slime) livingEntity).setSize(((Slime) stackedEntity.getLivingEntity()).getSize());
            }
            plugin.getNMSAdapter().playDeathSound(livingEntity);
            livingEntity.setHealth(0);
        }
    }

    @Override
    public void performKillAll(){
        performKillAll(false);
    }

    @Override
    public void performKillAll(boolean applyTaskFilter) {
        performKillAll(entity -> true, item -> true, applyTaskFilter);
    }

    @Override
    public void performKillAll(Predicate<Entity> entityPredicate, Predicate<Item> itemPredicate) {
        performKillAll(entityPredicate, itemPredicate, false);
    }

    @Override
    public void performKillAll(Predicate<Entity> entityPredicate, Predicate<Item> itemPredicate, boolean applyTaskFilter) {
        if(!Bukkit.isPrimaryThread()){
            Executor.sync(() -> performKillAll(entityPredicate, itemPredicate, applyTaskFilter));
            return;
        }

        List<Entity> entityList = new ArrayList<>();

        for(World world : Bukkit.getWorlds()){
            if(!applyTaskFilter || plugin.getSettings().killTaskEntitiesWorlds.isEmpty() ||
                    plugin.getSettings().killTaskEntitiesWorlds.contains(world.getName())) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    entityList.addAll(Arrays.stream(chunk.getEntities())
                            .filter(entity -> entity instanceof LivingEntity).collect(Collectors.toList()));
                }
            }
            if(!applyTaskFilter || plugin.getSettings().killTaskItemsWorlds.isEmpty() ||
                    plugin.getSettings().killTaskItemsWorlds.contains(world.getName())) {
                for (Chunk chunk : world.getLoadedChunks()) {
                    entityList.addAll(Arrays.stream(chunk.getEntities())
                            .filter(entity -> entity instanceof Item).collect(Collectors.toList()));
                }
            }
        }

        Executor.async(() -> {
            entityList.stream()
                    .filter(entity -> EntityUtils.isStackable(entity) && entityPredicate.test(entity) &&
                            (!applyTaskFilter || (GeneralUtils.containsOrEmpty(plugin.getSettings().killTaskEntitiesWhitelist, WStackedEntity.of(entity)) &&
                                    !GeneralUtils.contains(plugin.getSettings().killTaskEntitiesBlacklist, WStackedEntity.of(entity)))))
                    .forEach(entity -> {
                        StackedEntity stackedEntity = WStackedEntity.of(entity);
                        if(!applyTaskFilter || (((plugin.getSettings().killTaskStackedEntities && stackedEntity.getStackAmount() > 1) ||
                                (plugin.getSettings().killTaskUnstackedEntities && stackedEntity.getStackAmount() <= 1)) && !stackedEntity.hasNameTag()))
                            stackedEntity.remove();
                    });

            if(plugin.getSettings().killTaskStackedItems) {
                entityList.stream()
                        .filter(entity -> entity instanceof Item && ItemUtils.canPickup((Item) entity) && itemPredicate.test((Item) entity) &&
                                (!applyTaskFilter || (GeneralUtils.containsOrEmpty(plugin.getSettings().killTaskItemsWhitelist, ((Item) entity).getItemStack().getType().name()) &&
                                        !plugin.getSettings().killTaskItemsBlacklist.contains(((Item) entity).getItemStack().getType().name()))))
                        .forEach(entity -> {
                            StackedItem stackedItem = WStackedItem.of(entity);
                            if(!applyTaskFilter || (plugin.getSettings().killTaskStackedItems && stackedItem.getStackAmount() > 1) ||
                                    (plugin.getSettings().killTaskUnstackedItems && stackedItem.getStackAmount() <= 1))
                                stackedItem.remove();
                        });
            }

            for(Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.isOp())
                    Locale.KILL_ALL_OPS.send(pl);
            }
        });
    }

    @Override
    public StackedSnapshot getStackedSnapshot(Chunk chunk, boolean loadData) {
        return getStackedSnapshot(chunk);
    }

    @Override
    public StackedSnapshot getStackedSnapshot(Chunk chunk) {
        return new WStackedSnapshot(chunk);
    }

    /*
     * Loot loot methods
     */

    @Override
    public LootTable getLootTable(LivingEntity livingEntity) {
        return plugin.getLootHandler().getLootTable(livingEntity);
    }

}
