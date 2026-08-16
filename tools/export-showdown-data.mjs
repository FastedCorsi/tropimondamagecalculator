import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {Generations} from '@pkmn/data';
import {Dex} from '@pkmn/dex';

const require = createRequire(import.meta.url);
const root = path.resolve(import.meta.dirname, '..');
const target = path.join(root, 'src/main/resources/assets/tropimon_damage_calc/showdown/showdown-data.json');
const gens = new Generations(Dex);

const out = {
  source: {
    pkmnData: require('@pkmn/data/package.json').version,
    pkmnDex: require('@pkmn/dex/package.json').version,
    pokemonShowdown: require('pokemon-showdown/package.json').version,
    generatedFrom: '@pkmn/data + @pkmn/dex, synced with Pokemon Showdown data',
  },
  defaultGeneration: 9,
  generations: {},
};

function clean(value) {
  if (value === undefined || value === null || typeof value === 'function') return undefined;
  if (Array.isArray(value)) return value.map(clean).filter(v => v !== undefined);
  if (typeof value === 'object') {
    const result = {};
    for (const [key, raw] of Object.entries(value)) {
      if (['dex', 'moves', 'species', 'items', 'abilities', 'natures', 'types'].includes(key)) continue;
      const cleaned = clean(raw);
      if (cleaned !== undefined) result[key] = cleaned;
    }
    return result;
  }
  return value;
}

for (let number = 1; number <= 9; number++) {
  const gen = gens.get(number);
  const data = {species: {}, moves: {}, items: {}, abilities: {}, natures: {}, types: {}};

  for (const species of gen.species) data.species[species.id] = clean({
    id: species.id,
    name: species.name,
    num: species.num,
    gen: species.gen,
    types: species.types,
    baseStats: species.baseStats,
    abilities: species.abilities,
    weightkg: species.weightkg,
    baseSpecies: species.baseSpecies,
    forme: species.forme,
    evos: species.evos,
    prevo: species.prevo,
    nfe: species.nfe,
    isNonstandard: species.isNonstandard,
  });

  for (const move of gen.moves) data.moves[move.id] = clean({
    id: move.id,
    name: move.name,
    num: move.num,
    gen: move.gen,
    type: move.type,
    category: move.category,
    basePower: move.basePower,
    accuracy: move.accuracy,
    pp: move.pp,
    priority: move.priority,
    target: move.target,
    flags: move.flags,
    status: move.status,
    volatileStatus: move.volatileStatus,
    secondaries: move.secondaries,
    shortDesc: move.shortDesc,
    desc: move.desc,
    isNonstandard: move.isNonstandard,
    critRatio: move.critRatio,
    drain: move.drain,
    recoil: move.recoil,
    multihit: move.multihit,
    willCrit: move.willCrit,
    overrideOffensiveStat: move.overrideOffensiveStat,
    overrideDefensiveStat: move.overrideDefensiveStat,
    defensiveCategory: move.defensiveCategory,
  });

  for (const item of gen.items) data.items[item.id] = clean({
    id: item.id,
    name: item.name,
    num: item.num,
    gen: item.gen,
    shortDesc: item.shortDesc,
    desc: item.desc,
    isNonstandard: item.isNonstandard,
    isChoice: item.isChoice,
    megaStone: item.megaStone,
    zMove: item.zMove,
    naturalGift: item.naturalGift,
    fling: item.fling,
  });

  for (const ability of gen.abilities) data.abilities[ability.id] = clean({
    id: ability.id,
    name: ability.name,
    num: ability.num,
    gen: ability.gen,
    shortDesc: ability.shortDesc,
    desc: ability.desc,
    isNonstandard: ability.isNonstandard,
  });

  for (const nature of gen.natures) data.natures[nature.id] = clean({
    id: nature.id,
    name: nature.name,
    plus: nature.plus,
    minus: nature.minus,
  });

  for (const type of gen.types) data.types[type.id] = clean({
    id: type.id,
    name: type.name,
    effectiveness: type.effectiveness,
    damageTaken: type.damageTaken,
  });

  out.generations[String(number)] = data;
}

fs.mkdirSync(path.dirname(target), {recursive: true});
fs.writeFileSync(target, JSON.stringify(out));
console.log(`Wrote ${target}`);
