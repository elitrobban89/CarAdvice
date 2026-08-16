package com.caradvice.service;

import com.caradvice.repository.RateLimitLogRepository;
import com.caradvice.repository.SavedSearchRepository;
import com.caradvice.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trattmätning för prenumerationen.
 *
 * <p>Motivet: fram till 2026-08-16 gick det inte att svara på den enda fråga som avgör om
 * betalmodellen fungerar — hur många som registrerar sig och hur mycket tjänsten används.
 * Stripe ser bara botten av tratten (nådde kassan → betalade); alla som skapat konto men
 * aldrig klickat på köpknappen är osynliga där. Utan den siffran går det inte att skilja
 * "ingen hittar hit" från "många kommer men vill inte betala", och de två slutsatserna
 * leder åt helt olika håll.
 *
 * <p>Sökhistoriken är grovhuggen med flit: {@code rate_limit_log} finns för att räkna
 * kvoter, inte för statistik, och städas av {@code CarController.cleanupRateLimitLogs}.
 * Fönstret nedan får därför aldrig vara längre än städningens gräns — annars ser siffran
 * ut att falla när det i själva verket är raderna som försvunnit.
 */
@Service
public class UsageStatsService {

    /**
     * Så många konton krävs innan {@code conversionPct} får ett värde. Gränsen är satt lågt
     * med flit — den ska stoppa de vilseledande talen (1 av 1 = 100 %), inte vänta på
     * statistisk signifikans. Höj den inte utan att komma ihåg att ett tomt fält läses som
     * "vet inte", vilket är rätt svar så länge trafiken är enstaka besökare.
     */
    private static final int MIN_KONTON_FOR_KONVERTERING = 20;

    private final UserRepository userRepo;
    private final SavedSearchRepository savedSearchRepo;
    private final RateLimitLogRepository rateLimitLogRepo;

    public UsageStatsService(UserRepository userRepo, SavedSearchRepository savedSearchRepo,
                             RateLimitLogRepository rateLimitLogRepo) {
        this.userRepo = userRepo;
        this.savedSearchRepo = savedSearchRepo;
        this.rateLimitLogRepo = rateLimitLogRepo;
    }

    public Map<String, Object> snapshot() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Map<String, Object> out = new LinkedHashMap<>();

        long accounts = userRepo.count();
        long active = userRepo.countBySubscriptionStatus("active");
        long everPaid = userRepo.countBySubscriptionStartedAtIsNotNull();

        out.put("accounts", accounts);
        out.put("accountsLast7Days", userRepo.countByCreatedAtAfter(now.minusDays(7)));
        out.put("accountsLast30Days", userRepo.countByCreatedAtAfter(now.minusDays(30)));
        out.put("activeSubscribers", active);
        out.put("everSubscribed", everPaid);
        // everSubscribed - activeSubscribers = de som betalat och slutat. Är den noll medan
        // everSubscribed växer är churn inte problemet.
        out.put("churnedSubscribers", Math.max(0, everPaid - active));
        out.put("cancelPending", userRepo.countByCancelAtPeriodEndTrue());
        out.put("savedSearches", savedSearchRepo.count());

        // Andelen som konverterar. Talet lämnas tomt tills underlaget bär det: med ett enda
        // konto som en gång betalat blir svaret "100 %", vilket är det bästa tänkbara talet
        // och samtidigt helt innehållslöst — och det är precis den siffran betalmodellen
        // skulle bedömas på. En kvot vars nämnare är ensiffrig är inget mätresultat.
        boolean barUnderlaget = accounts >= MIN_KONTON_FOR_KONVERTERING;
        out.put("conversionPct", barUnderlaget
                ? Math.round(everPaid * 1000.0 / accounts) / 10.0 : null);
        out.put("conversionNote", barUnderlaget
                ? accounts + " konton bakom talet."
                : "För få konton (" + accounts + " av " + MIN_KONTON_FOR_KONVERTERING
                        + ") — andelen säger inget än.");

        Map<String, Object> searches = new LinkedHashMap<>();
        searches.put("last24h", rateLimitLogRepo.countRecentRecommend(now.minusHours(24)));
        searches.put("last7Days", rateLimitLogRepo.countRecentRecommend(now.minusDays(7)));
        searches.put("distinctKeysLast7Days", rateLimitLogRepo.countDistinctKeysSince(now.minusDays(7)));
        searches.put("note", "Prenumeranter loggas inte — deras sökningar räknas inte mot någon kvot.");
        out.put("searches", searches);

        return out;
    }
}
