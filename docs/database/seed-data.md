# Seed data and product images

`seed.sql` provides the nine English dishes from the PRD, their fixed allergens, all documented customization groups, and published menus for both the run date and following date. Each item starts with 20 servings, so the demo can exercise ordering, low stock, sold-out behavior, cancellation restore, and dashboard changes.

The supplied source directory is:

```text
~/Downloads/WeBox-PRD-候选人交付包/product_images/
```

During implementation, visually appropriate files will be copied (not referenced by an absolute machine path) into a versioned seed-media directory and renamed to stable URLs:

```text
image_01_s-*.jpg -> image_01.jpg -> /media/seed/image_01.jpg
...
image_09_s-*.jpg -> image_09.jpg -> /media/seed/image_09.jpg
```

The current numeric mapping in `seed.sql` is provisional until the images are visually matched to dishes. All 20 supplied images remain available for selecting the best nine. Copyright/source metadata is not included in the delivery package; production use requires confirming asset rights.

Demo users are created by an idempotent local-profile bootstrap component, not raw SQL, because passwords must be BCrypt-hashed and must not be committed. Proposed local defaults are documented through `.env.example`, while real passwords remain environment variables. This bootstrap never runs in production profiles.

