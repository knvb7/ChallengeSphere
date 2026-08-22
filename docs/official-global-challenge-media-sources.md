# Official Global Challenge Media Sources

The official global challenge catalog uses controlled copies under
`official/global-challenges/` in the configured ChallengeSphere media bucket.
Challenge and profile rows store object keys so the API can return signed
readable URLs in each environment.

All cover photographs below were marked **Free to use under the Unsplash
License** on their source pages when selected on 2026-05-27. The Unsplash
License permits free commercial and non-commercial use without permission;
attribution is retained here for auditability.

| Hosted Object Key | Theme | Source / Credit |
| --- | --- | --- |
| `official/global-challenges/Challengesphere-logo.png` | Official profile logo | Existing ChallengeSphere application asset |
| `official/global-challenges/official-fun-social-bright.jpg` | Fun and social | Sasha Matveeva, [Unsplash photo](https://unsplash.com/photos/Z2k2Yod4vCY) |
| `official/global-challenges/official-creative.jpg` | Creative activities | Sarah Brown, [Unsplash photo](https://unsplash.com/photos/AmK0hhqJBT4) |
| `official/global-challenges/official-reading-study.jpg` | Reading, journaling and study | Nubelson Fernandes, [Unsplash photo](https://unsplash.com/photos/YHFNokH-0G4) |
| `official/global-challenges/official-fitness.jpg` | Fitness and walking | Anupam Mahapatra, [Unsplash photo](https://unsplash.com/photos/Vz0RbclzG_w) |
| `official/global-challenges/official-wellness.jpg` | Health and meditation | Nahiyan Ahmed, [Unsplash photo](https://unsplash.com/photos/kJM1Z-FOuDc) |
| `official/global-challenges/official-productivity.jpg` | Work, career and planning | Surface, [Unsplash photo](https://unsplash.com/photos/1shdfk7mQzw) |

License reference: [Unsplash License](https://unsplash.com/license).

Migration `V52__add_official_challenge_covers.sql` maps every curated template to
one of these controlled object keys, repairs existing official cohorts that have
no cover, and installs a trigger so each future rotating cohort receives its
template cover when it is published.

## Superseded Stored Object

`official/global-challenges/official-fun-social.jpg` was initially uploaded
from a Colton Sturgeon [Unsplash photo](https://unsplash.com/photos/a6PwJLSr-LY).
It is not referenced by the catalog. Bucket retention prevents deleting or
overwriting it until 2027-05-27, so the brighter replacement is stored at
`official-fun-social-bright.jpg`.
