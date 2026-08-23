package com.ironside.challengesphere.persistence.dynamodb.operations;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Version-controlled copy of the evergreen official cohort catalog previously
 * seeded by V33. Disabled one-off templates are intentionally excluded because
 * the rolling publisher never materializes them.
 */
public final class OfficialChallengeCatalog {

    private static final LocalDate AVAILABLE_THROUGH = LocalDate.of(2027, 7, 31);

    private static final List<Template> TEMPLATES = List.of(
            template(
                    "move-your-way-10",
                    "Move Your Way 10",
                    "Choose any comfortable movement for ten minutes and cheer on every kind of progress.",
                    "Fitness,Health",
                    "fitness,health,movement",
                    "Move for about ten minutes at your own comfortable pace. Walking, rolling, seated movement, stretching, dance, and assisted movement all count.",
                    "Build a friendly movement rhythm without comparison or pressure.",
                    14,
                    "FLEXIBLE",
                    1
            ),
            template(
                    "stretch-and-reset",
                    "Stretch & Reset",
                    "Take a short, gentle reset using stretches or mobility movements that feel comfortable.",
                    "Fitness,Health",
                    "fitness,health,mobility",
                    "Spend three to five minutes on gentle stretching or mobility, stopping anything that feels painful or unsafe.",
                    "Create a small, accessible reset in the day.",
                    7,
                    "FLEXIBLE",
                    2
            ),
            template(
                    "walk-roll-or-wander",
                    "Walk, Roll or Wander",
                    "Take a comfortable change-of-scene break indoors or outside and notice something new.",
                    "Fitness,Lifestyle",
                    "fitness,lifestyle,movement",
                    "Choose a short walk, roll, assisted outing, or indoor route that is comfortable and permitted for you.",
                    "Add movement and curiosity without pace targets.",
                    14,
                    "FLEXIBLE",
                    3
            ),
            template(
                    "one-song-dance-break",
                    "One-Song Dance Break",
                    "Pick one song, move in any way that feels good, and bring a playful energy boost.",
                    "Fitness,Lifestyle",
                    "fitness,lifestyle,fun",
                    "Move for one song using standing, seated, upper-body, or imagined choreography. Keep the space clear and comfortable.",
                    "Create a quick, playful energy reset.",
                    7,
                    "FLEXIBLE",
                    4
            ),
            template(
                    "hydration-hello",
                    "Hydration Hello",
                    "Pause for a drink that suits you and use the check-in as a friendly reminder, not a volume contest.",
                    "Health,Lifestyle",
                    "health,lifestyle,wellness",
                    "Take one mindful drink that is appropriate for you. There is no required amount and personal medical guidance comes first.",
                    "Use social support as a gentle reminder.",
                    14,
                    "FLEXIBLE",
                    5
            ),
            template(
                    "one-calm-minute",
                    "One Calm Minute",
                    "Make one quiet minute part of the day and swap simple ways to pause.",
                    "Health,Lifestyle",
                    "health,lifestyle,calm",
                    "Pause for one minute with a calming activity of your choice, such as quiet breathing, listening, or noticing your surroundings.",
                    "Build a low-pressure pause into the day.",
                    7,
                    "FLEXIBLE",
                    6
            ),
            template(
                    "wind-down-win",
                    "Wind-Down Win",
                    "Try one small ritual before your main sleep period without comparing sleep scores.",
                    "Health,Lifestyle",
                    "health,lifestyle,winddown",
                    "Choose one comfortable wind-down action without setting a required bedtime or sleep target.",
                    "Discover practical ways to make evenings calmer.",
                    14,
                    "FLEXIBLE",
                    7
            ),
            template(
                    "fresh-view-break",
                    "Fresh View Break",
                    "Step away from the usual view for a few minutes—outside, by a window, or elsewhere indoors.",
                    "Health,Lifestyle",
                    "health,lifestyle,break",
                    "Take a short break in any comfortable setting that gives you a change of view. Outside is always optional.",
                    "Make restorative breaks easier to remember.",
                    7,
                    "FLEXIBLE",
                    8
            ),
            template(
                    "read-for-ten",
                    "Read for Ten",
                    "Read or listen for ten minutes in any format or language, then share one spoiler-free thought.",
                    "Learning,Lifestyle",
                    "learning,lifestyle,reading",
                    "Spend about ten minutes with a book, article, audiobook, Braille text, or another reading format you enjoy.",
                    "Build a flexible reading rhythm and trade recommendations.",
                    14,
                    "FLEXIBLE",
                    9
            ),
            template(
                    "learn-one-share-one",
                    "Learn One, Share One",
                    "Learn one small thing and make the group smarter through friendly, bite-sized discoveries.",
                    "Learning,Social",
                    "learning,social,curiosity",
                    "Learn one small, non-sensitive fact or technique from a source you trust and explain it in your own words.",
                    "Turn individual curiosity into shared learning.",
                    7,
                    "FLEXIBLE",
                    10
            ),
            template(
                    "word-around-the-world",
                    "Word Around the World",
                    "Share a useful word or phrase from any language and celebrate how differently people communicate.",
                    "Learning,Social",
                    "learning,social,language",
                    "Choose a respectful word or short phrase, check its meaning, and include the language or signing system it comes from.",
                    "Learn through respectful cultural exchange.",
                    7,
                    "FLEXIBLE",
                    11
            ),
            template(
                    "one-focus-sprint",
                    "One Focus Sprint",
                    "Choose one task, focus for a short block, and use the group for gentle accountability.",
                    "Productivity,Learning",
                    "productivity,learning,focus",
                    "Choose one suitable task and focus for ten to twenty-five minutes, taking any breaks or accommodations you need.",
                    "Make starting easier through shared accountability.",
                    14,
                    "FLEXIBLE",
                    12
            ),
            template(
                    "five-minute-reset",
                    "Five-Minute Reset",
                    "Spend five minutes improving one tiny physical or digital space and enjoy the small win.",
                    "Productivity,Lifestyle",
                    "productivity,lifestyle,reset",
                    "Reset one small area for about five minutes without lifting, discarding, or handling anything unsafe.",
                    "Create visible progress with very low effort.",
                    7,
                    "FLEXIBLE",
                    13
            ),
            template(
                    "tomorrows-top-three",
                    "Tomorrow's Top Three",
                    "Choose up to three priorities for the next day and build a calm planning rhythm.",
                    "Productivity,Lifestyle",
                    "productivity,lifestyle,planning",
                    "Write up to three realistic priorities for your next day. The tasks themselves can stay completely private.",
                    "Reduce next-day decision friction.",
                    14,
                    "FLEXIBLE",
                    14
            ),
            template(
                    "practice-a-small-skill",
                    "Practice a Small Skill",
                    "Pick a safe skill you want to improve and let consistent practice matter more than perfection.",
                    "Learning,Productivity",
                    "learning,productivity,practice",
                    "Practice one safe, age-appropriate skill for a short session, using suitable supervision or instruction where needed.",
                    "Turn small practice sessions into visible momentum.",
                    14,
                    "FLEXIBLE",
                    15
            ),
            template(
                    "curiosity-capture",
                    "Curiosity Capture",
                    "Notice one everyday question, find a reliable answer, and share the discovery.",
                    "Learning,Creativity",
                    "learning,creativity,curiosity",
                    "Pick one harmless everyday question and check a reliable source before sharing a short answer in your own words.",
                    "Strengthen curiosity and source-checking habits.",
                    7,
                    "FLEXIBLE",
                    16
            ),
            template(
                    "appreciation-ping",
                    "Appreciation Ping",
                    "Give someone a sincere thank-you without making private conversations public.",
                    "Social,Lifestyle",
                    "social,lifestyle,appreciation",
                    "Offer one respectful thank-you or note of appreciation with no expectation that the other person responds.",
                    "Build a warmer social habit while respecting privacy.",
                    7,
                    "FLEXIBLE",
                    17
            ),
            template(
                    "kindness-relay",
                    "Kindness Relay",
                    "Do one small, respectful act of kindness and share the action—not the recipient.",
                    "Social,Lifestyle",
                    "social,lifestyle,kindness",
                    "Choose a small helpful action that respects consent, property, local rules, and the other person's dignity.",
                    "Encourage considerate action without exploiting recipients.",
                    7,
                    "FLEXIBLE",
                    20
            ),
            template(
                    "same-sky-snapshot",
                    "Same-Sky Snapshot",
                    "Share one patch of sky and notice how light, colour, and weather differ.",
                    "Social,Creativity",
                    "social,creativity,sky",
                    "Photograph the sky only from a safe, permitted place. Staying indoors and shooting through a window both count.",
                    "Create a simple shared moment across locations.",
                    7,
                    "FLEXIBLE",
                    21
            ),
            template(
                    "tiny-creative",
                    "Tiny Creative",
                    "Make something for five minutes in any medium and let experimentation count more than polish.",
                    "Creativity,Lifestyle",
                    "creativity,lifestyle,making",
                    "Create for about five minutes using a safe medium you already have. Drawing, writing, music, movement, arranging, and crafting all count.",
                    "Lower the pressure to begin creative work.",
                    14,
                    "FLEXIBLE",
                    22
            ),
            template(
                    "seven-day-photo-quest",
                    "Seven-Day Photo Quest",
                    "Use a simple daily theme such as colour, texture, shape, or light and compare interpretations.",
                    "Creativity,Social",
                    "creativity,social,photography",
                    "Take one original photo responding to the theme while respecting people, property, and photography rules.",
                    "Practice creative observation in a privacy-aware way.",
                    7,
                    "PHOTO_REQUIRED",
                    23
            ),
            template(
                    "reuse-once",
                    "Reuse Once",
                    "Find one practical way to reuse, repair, refill, borrow, or repurpose something you already have.",
                    "Lifestyle,Creativity",
                    "lifestyle,creativity,reuse",
                    "Choose one clean, safe item without handling hazardous materials or buying anything new.",
                    "Encourage practical resourcefulness without spending pressure.",
                    7,
                    "FLEXIBLE",
                    25
            )
    );

    private OfficialChallengeCatalog() {
    }

    public static List<Template> eligibleOn(LocalDate date) {
        return TEMPLATES.stream()
                .filter(template -> !template.availableThrough().isBefore(date))
                .sorted(Comparator.comparingInt(Template::rotationOrder))
                .toList();
    }

    private static Template template(
            String key,
            String title,
            String description,
            String categories,
            String hashtags,
            String rules,
            String benefits,
            int numberOfDays,
            String proofMode,
            int rotationOrder
    ) {
        return new Template(
                key,
                title,
                description,
                categories,
                hashtags,
                rules,
                benefits,
                numberOfDays,
                proofMode,
                rotationOrder,
                AVAILABLE_THROUGH
        );
    }

    public record Template(
            String key,
            String title,
            String description,
            String categories,
            String hashtags,
            String rules,
            String benefits,
            int numberOfDays,
            String proofMode,
            int rotationOrder,
            LocalDate availableThrough
    ) {
    }
}
