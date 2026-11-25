// Supporter ranks as players see them on the store. Prices come from Tebex; this is what each rank gives.
// Keep in step with the game's Ranks/rewards.json and SupporterRanks (XP bonus). Ids starborn, celestial and cosmic
// show as Settler, Steward and Elder.
export const LADDER=['wanderer','starborn','celestial','cosmic'];
export const RANK_IDS=[...LADDER,'founder','eternal'];
export const RANKS={
 wanderer:{name:'Wanderer',color:'#a9c8a0',perks:['Wanderer name title and colour','2 more titles','1 cosmetic','1 pet','2 decorations','A house','5% more season pass XP']},
 starborn:{name:'Settler',color:'#8fd3ff',perks:['Everything Wanderer gives','Settler name title and colour','2 more titles','1 cosmetic','1 pet','2 decorations','A house','1 free plot voucher','10% more season pass XP']},
 celestial:{name:'Steward',color:'#b3a6ff',perks:['Everything Wanderer and Settler give','Steward name title and colour','2 more titles','2 cosmetics','1 pet','2 decorations','A house','1 free plot voucher','20% more season pass XP']},
 cosmic:{name:'Elder',color:'#ff8fd8',perks:['Everything the ranks below give','Elder name title and colour','2 more titles','2 cosmetics','1 pet','2 decorations','A house','1 free plot voucher','1 free guild plot voucher','30% more season pass XP']},
 founder:{name:'Founder',color:'#ffd447',perks:['Founder name title and colour','Founding and Member titles','Founders Badge','1 cosmetic','Corn King pet','A house and a decoration','1 free plot voucher','2 free house hexagon vouchers','2 free production hexagon vouchers','20% more season pass XP, on top of your other ranks']},
 eternal:{name:'Eternal',color:'#5ef2e6',perks:['Eternal name title and colour, 2 more titles and a pet while subscribed','3 cosmetics, 2 decorations and a house to keep','2 free plot vouchers to keep','1 free guild plot voucher right away, yours to keep after 3 months','50% more season pass XP while subscribed, on top of your other ranks']}
};
