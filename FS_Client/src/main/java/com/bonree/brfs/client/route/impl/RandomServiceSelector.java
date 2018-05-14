package com.bonree.brfs.client.route.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.bonree.brfs.client.meta.ServiceMetaCache;
import com.bonree.brfs.client.route.ServiceSelector;
import com.bonree.brfs.common.service.Service;

public class RandomServiceSelector implements ServiceSelector {

    @Override
    public Service selectService(ServiceMetaCache serviceMetaCache) {
        Service service = null;
        List<String> firstIDs = new ArrayList<String>(serviceMetaCache.getDuplicaServerCache().keySet());
        if (firstIDs != null && !firstIDs.isEmpty()) {
            Random random = new Random();
            String randomFirstID = firstIDs.get(random.nextInt(firstIDs.size()));
            service = serviceMetaCache.getDuplicaServerCache().get(randomFirstID);
        }
        return service;
    }

}