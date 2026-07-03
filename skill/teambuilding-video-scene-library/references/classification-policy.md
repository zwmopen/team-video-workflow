# Classification Policy

Use exactly one physical destination for every normal clip.

Priority:

1. Specific activity project: kayaking, yacht, rafting, CS, cycling, water games.
2. Concrete scene: departure, accommodation, food, environment, night camp.
3. People mood or reaction.
4. `90_待人工分类`.

Examples:

- Lake aerial, island scenery, sunset -> `01_环境空镜`.
- Company gathering, bus, boarding, arrival -> `02_出发抵达`.
- Hotel, homestay, room, balcony, lake-view room -> `03_住宿空间`.
- Fish banquet, dishes, group meal, cheers -> `04_餐饮美食`.
- Kayaking, yacht, rafting, water challenge -> `05_项目活动/<actual project>`.
- Cycling, bicycle, lakeside ride, or "环湖骑行" -> `05_项目活动/湖边骑行`.
- Aerial/overhead lake scenery or "千岛湖风景俯拍" -> `01_环境空镜/千岛湖风景俯拍`.
- General lake/mountain scenery or "千岛湖风景" -> `01_环境空镜/千岛湖风景`.
- Scenic empty shots useful for transition/ending -> `01_环境空镜/风景空镜转场`.
- Bus interior, on-bus departure, boarding, or car-window departure shots -> `02_出发抵达/大巴集合出发`.
- Kayak/皮划艇 visual or text -> `05_项目活动/皮划艇`.
- Jet ski/摩托艇/water motorbike -> `05_项目活动/摩托艇`.
- Water park/water playground -> `05_项目活动/水上乐园`.
- Camping, tents, tarp, campsite, or "露营" -> `07_烧烤露营夜场/露营`.
- Eating at campsite or "露营吃东西" -> `07_烧烤露营夜场/露营吃东西`.
- Bonfire/campfire/fire circle -> `07_烧烤露营夜场/篝火`.
- Yacht deck, boat bow, boat rail, "湖水承包", or people clearly on a boat/lake surface -> `05_项目活动/游艇游湖`, even if nearby narration mentions lunch or dishes.
- Team race, group challenge, award, cooperation -> `06_团队互动`.
- Team group photo/合照/大合照 -> `06_团队互动/团队合照`.
- Barbecue, tarp, bonfire, fireworks, singing at night -> `07_烧烤露营夜场`.
- Laughing, cheering, waving, selfie reaction -> `08_人物反应`.
- Paddle close-up, food close-up, fire, equipment, hand detail -> `09_细节特写`.
- Return trip, goodbye, closing group photo, final CTA -> `10_收尾返程`.

If a clip contains people cheering on a yacht, save the file under `05_项目活动/游艇游湖` and write `欢呼` only as a semantic tag.

If narration and picture disagree, prioritize the visible picture and on-screen text for the physical folder. Narration can lead or lag the picture in edited Douyin Vlogs, so food words near a boat shot should not override an obvious yacht/lake visual.

Do not let one transcript keyword classify a whole source sequence. A voiceover sentence such as "出发去安吉" can cover aerial views, food, kayaking, rafting, bonfire, and lodging shots. In that case, classify every clip by visible picture first.

Bad example to avoid:

- Moving many clips into `02_出发抵达/大巴集合出发` only because the nearby transcript says `出发`, when the frames show aerial scenery, dishes, kayaking, fireworks, or rafting.

Correct behavior:

- Aerial mountain/scenic frames -> `01_环境空镜/<地点>风景` or `<地点>风景俯拍`.
- Dishes/table/fruit/drinks -> `04_餐饮美食/<concrete keyword>`.
- Kayak frames -> `05_项目活动/皮划艇`.
- Rafting/water chute frames -> `05_项目活动/漂流`.
- Fireworks/bonfire/barbecue/night gathering -> `07_烧烤露营夜场/<concrete keyword>`.
- Room/bed/pool/homestay -> `03_住宿空间/<concrete keyword>`.

If a clip contains multiple shots or the shot boundary is visibly wrong, route it to `90_待人工分类/待重新切分` and mark it as not directly usable until re-split.

## Visual Audit Correction Standard

When an existing library is messy, classify every clip by its visible picture from a contact sheet or keyframes. Do not inherit the old folder as truth.

Useful concrete keyword folders for team-building material:

- Environment: `<地点>风景`, `<地点>风景俯拍`, `云海山景`, `竹林山景`, `峡谷瀑布空镜`, `庾村小镇`, `莫干山天际塔`.
- Departure: `大巴集合出发`, `山路抵达`, `堵车路况`.
- Accommodation: `酒店民宿房间`, `民宿外观`, `民宿庭院`, `民宿泳池`, `湖景露台`, `别墅KTV棋牌室`, `温泉泡池`.
- Food: `农家菜`, `菜品餐桌`, `菜品特写`, `自助餐`, `早餐`, `下午茶点`, `千岛湖鱼宴`.
- Activities: `漂流`, `皮划艇`, `水上乐园`, `水上拓展`, `真人CS`, `山地越野车`, `茶山越野车`, `彩虹滑道`, `高山滑道`, `草地滑草`, `高空滑索`, `高空拓展`, `射箭`, `碰碰球`, `田园小火车`, `麻将`, `台球`, `KTV唱歌`, `剧本杀`, `桌游`, `电竞游戏`.
- Team interaction: `团队游戏挑战`, `草坪团建游戏`, `加油欢呼互动`, `团队合照`, `团队会议`.
- Night/camp: `露营`, `露营吃东西`, `烧烤`, `烤全羊`, `篝火`, `烟花`, `夜间团队互动`, `夜间娱乐`.
- Review-only: `口播讲解`, `方案讲解`, `图文海报`, `行程表截图`, `待复核画面`.

If a clip is a presenter explaining a plan with inserted pictures, put it in `90_待人工分类/方案讲解` or `90_待人工分类/图文海报`, even if the inserted picture contains food, rafting, or lodging. The clean b-roll folders should contain direct usable picture-first material.
