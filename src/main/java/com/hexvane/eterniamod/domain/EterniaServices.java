package com.hexvane.eterniamod.domain;

import com.hexvane.eterniamod.persistence.TransactionalStore;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** Hytale-free entry point. Production bootstrap must select a durable store and fail closed on errors. */
public final class EterniaServices {
    private final AccountService accounts;
    private final OwnershipService ownership;
    private final EconomyService economy;
    private final JournalService journal;
    private final HousingService housing;
    private final GuildService guilds;
    private final EscrowService escrow;
    private final MailService mail;
    private final MarketService market;
    private final TradeService trades;
    private final SeasonService seasons;
    private final ProvenanceService provenance;
    private final CommerceService commerce;
    private final PremiumService premium;
    private final CollectionService collection;
    private final CommerceIngressService commerceIngress;
    private final ActivitySourceService activitySources;
    private final PartyService parties;
    private final DiscoveryService discoveries;
    private final GuildBenefitService guildBenefits;
    private final GuildCommunicationService guildCommunications;
    public EterniaServices(TransactionalStore store,Clock clock,Supplier<UUID> ids) {
        accounts=new AccountService(store,clock,ids);ownership=new OwnershipService(store,clock,ids);
        economy=new EconomyService(store,clock,ids);journal=new JournalService(store,clock,ids);
        guilds=new GuildService(store,clock,ids);housing=new HousingService(store,clock,ids,ownership,journal);
        escrow=new EscrowService(store,clock,ids);mail=new MailService(store,clock,ids,escrow);
        market=new MarketService(store,clock,ids,economy,escrow);trades=new TradeService(store,clock,ids,economy,escrow);
        seasons=new SeasonService(store,clock,ids,ownership);
        provenance=new ProvenanceService(store,clock,ids);premium=new PremiumService(store,clock,ids,ownership);commerce=new CommerceService(store,clock,ids,ownership,premium);
        collection=new CollectionService(store,clock,ids,ownership);
        commerceIngress=new CommerceIngressService(store,clock,ids,commerce);
        activitySources=new ActivitySourceService(store,clock,ids,seasons,economy);
        parties=new PartyService(store,clock,ids);
        discoveries=new DiscoveryService(store,clock,ids,ownership);
        guildBenefits=new GuildBenefitService(store,clock,ids,ownership);
        guildCommunications=new GuildCommunicationService(store,clock,ids);
    }
    public EterniaServices(TransactionalStore store) { this(store,Clock.systemUTC(),UUID::randomUUID); }
    public AccountService accounts(){return accounts;}
    public OwnershipService ownership(){return ownership;}
    public EconomyService economy(){return economy;}
    public JournalService journal(){return journal;}
    public HousingService housing(){return housing;}
    public GuildService guilds(){return guilds;}
    public EscrowService escrow(){return escrow;}
    public MailService mail(){return mail;}
    public MarketService market(){return market;}
    public TradeService trades(){return trades;}
    public SeasonService seasons(){return seasons;}
    public ProvenanceService provenance(){return provenance;}
    public CommerceService commerce(){return commerce;}
    public PremiumService premium(){return premium;}
    public CollectionService collection(){return collection;}
    public CommerceIngressService commerceIngress(){return commerceIngress;}
    public ActivitySourceService activitySources(){return activitySources;}
    public PartyService parties(){return parties;}
    public DiscoveryService discoveries(){return discoveries;}
    public GuildBenefitService guildBenefits(){return guildBenefits;}
    public GuildCommunicationService guildCommunications(){return guildCommunications;}
    public record Overview(UUID playerId,AccountService.Account account,EconomyService.Balance coins,
        List<OwnershipService.Grant> grants,HousingService.Slot housing,GuildService.Membership guild) {}
    /** Read projection for an already-authorized caller. Do not expose this without account authorization. */
    public Overview overview(UUID playerId) {
        return new Overview(playerId,accounts.find(playerId).orElse(null),economy.balance(Owner.player(playerId)),
            ownership.grants(Owner.player(playerId)),housing.find(Owner.player(playerId)).orElse(null),guilds.membership(playerId).orElse(null));
    }
}
