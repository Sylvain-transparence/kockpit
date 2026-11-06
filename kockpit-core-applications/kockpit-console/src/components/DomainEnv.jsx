import React, {useEffect, useState} from "react";
import {getConfig} from "../services/api.js";
import {useSearchParams} from "react-router-dom";
import {getAggregatedManifests} from "../services/manifestApi.js";
import {StarIcon as StarOutlineIcon} from "@heroicons/react/24/outline";
import {StarIcon as StarSolidIcon} from "@heroicons/react/24/solid";
import {findFavoriteIndex, isFavoriteConfig, loadFavoriteConfig, saveFavoriteConfig} from "../settings/favoriteConfig.js";

// onEmpty: called when the user may see no manifest at all, instead of showing it as an error
function DomainEnv({onConfigLoaded, domainEnvChanged, selectedIndex = 0, onSelectedIndex, onEmpty}) {
    const [searchParams] = useSearchParams();
    const [options, setOptions] = useState([]);
    const [configError, setConfigError] = useState(null);
    const [favorite, setFavorite] = useState(loadFavoriteConfig);

    if (searchParams.has('selectedConfig')) {
        onSelectedIndex(parseInt(searchParams.get('selectedConfig')));
        selectedIndex = parseInt(searchParams.get('selectedConfig'));
    }
    useEffect(() => {
        getAggregatedManifests().then(config => {
            if (!Array.isArray(config)) {
                setConfigError('Failed to load config: invalid response from server');
                return;
            }
            if (config.length === 0) {
                if (onEmpty) {
                    onEmpty();
                    return;
                }
                setConfigError('No manifests found on the backend');
                return;
            }
            setConfigError(null);
            // the favorite is the default, unless a link asks for a given config (selectedConfig)
            let initialIndex = selectedIndex;
            if (!searchParams.has('selectedConfig')) {
                const favoriteIndex = findFavoriteIndex(config);
                if (favoriteIndex > -1) {
                    initialIndex = favoriteIndex;
                    onSelectedIndex(favoriteIndex);
                }
            }
            onConfigLoaded(config, initialIndex);
            let opts = [];
            let index = 0;
            config.forEach(cfg => {
                opts.push({
                    name: cfg.name,
                    domain: cfg.domain,
                    env: cfg.env,
                    selected: index === initialIndex
                });
                index++;
            });
            setOptions(opts);

        }).catch(e => setConfigError('Failed to load config: ' + (e?.message || 'unknown error')))
    }, []);

    function onChange(e) {
        // Options are identified by their position: several manifests can share the same
        // domain/env, so "domain/env" alone always resolved to the first of them.
        const index = parseInt(e.target.value, 10);
        const option = options[index];
        if (!option) {
            return;
        }
        onSelectedIndex(index);
        domainEnvChanged(option.domain, option.env, index);
    }

    const selectedValue = selectedIndex >= 0 && options.length > selectedIndex
        ? String(selectedIndex)
        : '';
    const selectedOption = options[selectedIndex];
    const selectedIsFavorite = isFavoriteConfig(favorite, selectedOption);

    // the star sets the selected config as favorite, or clears it when it already is
    function toggleFavorite() {
        if (!selectedOption) {
            return;
        }
        const updated = selectedIsFavorite ? null : {domain: selectedOption.domain, env: selectedOption.env};
        saveFavoriteConfig(updated);
        setFavorite(updated);
    }

    if (options.length === 0 && onEmpty && !configError) {
        return null;
    }

    if (configError) {
        return (
            <div className="px-2 py-2 sm:px-2 lg:px-2">
                <div className="text-red-600 text-sm font-medium bg-red-50 border border-red-200 rounded px-3 py-2">
                    {configError}
                </div>
            </div>
        );
    }

    return (
        <div className="px-2 py-2 sm:px-2 lg:px-2">
            <div className="flex items-center gap-2">
                <div className="flex flex-col min-w-[220px]">
                    <select
                        onChange = {onChange}
                        value = {selectedValue}
                        className="w-full rounded-lg border border-gray-300 shadow focus:border-blue-500 focus:ring-2 focus:ring-blue-200 focus:outline-none transition sm:text-sm bg-white px-3 py-2"
                        style={{ minHeight: '44px' }}
                    >
                        {
                            options.map((option, index) => (
                                <option key={option.domain+option.env+index} value={String(index)} title={option.name}>
                                    {option.domain} / {option.env}{isFavoriteConfig(favorite, option) ? ' ★' : ''}
                                </option>
                            ))
                        }
                    </select>
                </div>
                <button
                    type="button"
                    onClick={toggleFavorite}
                    disabled={!selectedOption}
                    aria-pressed={selectedIsFavorite}
                    title={selectedIsFavorite ? 'Default config - click to remove it' : 'Load this config by default'}
                    aria-label={selectedIsFavorite ? 'Remove the default config' : 'Set as default config'}
                    className="flex items-center justify-center rounded-lg border border-gray-300 bg-white shadow hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-blue-200 transition disabled:opacity-50"
                    style={{ minHeight: '44px', minWidth: '44px' }}
                >
                    {selectedIsFavorite
                        ? <StarSolidIcon className="h-5 w-5 text-yellow-400" />
                        : <StarOutlineIcon className="h-5 w-5 text-gray-400" />}
                </button>
            </div>
        </div>
    )
}


export default DomainEnv;
