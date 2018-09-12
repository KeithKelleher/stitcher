import os
import sys
import cookielib
import urllib
import urllib2
import json
import time

# NOTE: TO RUN you will need to download UNII names file from FDA SRS webpage into temp folder
# See --- open('../temp/UNIIs-2018-09-07/UNII Names 31Aug2018.txt', 'r') below

cookies = cookielib.CookieJar()

opener = urllib2.build_opener(
    urllib2.HTTPRedirectHandler(),
    urllib2.HTTPHandler(debuglevel=0),
    urllib2.HTTPSHandler(debuglevel=0),
    urllib2.HTTPCookieProcessor(cookies))
opener.addheaders = [
    ('User-agent', ('Mozilla/4.0 (compatible; MSIE 6.0; '
                    'Windows NT 5.2; .NET CLR 1.1.4322)'))
]

site = 'https://stitcher.ncats.io/'
#site = 'http://localhost:8080/'

def requestJson(uri):
    try:
        handle = opener.open(uri)
        response = handle.read()
        handle.close()
        obj = json.loads(response)
        return obj
    except:
        sys.stderr.write("failed: "+uri+"\n")
        sys.stderr.flush()
        time.sleep(5)

def uniiClashes(unii2stitch, stitch):
    for node in stitch['sgroup']['members']:
        if node.has_key('stitches') and node['stitches'].has_key('I_UNII'):
            uniis = node['stitches']['I_UNII']
            if len(uniis[0]) == 1: # I_UNII: "C50C4V19SU",
                unii = uniis
                uniis = []
                uniis.append(unii)
            #else I_UNII: ["01YAE03M7J"],
            for unii in uniis:
                if not unii2stitch.has_key(unii):
                    unii2stitch[unii] = []
                if stitch['id'] not in unii2stitch[unii]:
                    unii2stitch[unii].append(stitch['id'])
    return unii2stitch

def approvedStitches(approved, stitch):
    if stitch['approved'] == True:
        apprYear = "not given"
        if stitch.has_key('approvedYear'):
            apprYear = stitch['approvedYear']
        parent = stitch['sgroup']['parent']
        rank = stitch['rank']
        unii = ''
        for node in stitch['sgroup']['members']:
            if node['node'] == parent:
                if not node.has_key('id'):
                    unii = node['name']
                else:
                    unii = node['id']
        name = getName(stitch)
        approved[unii] = [apprYear, parent, rank]
    return approved

NMEs = []
def nmeStitches(stitch2nmes, stitch):
    key = stitch['id']
    entries = []
    for node in stitch['sgroup']['members']:
        if node['source'] == 'G-SRS, July 2018' and node['id'] in NMEs:
            entries.append(node['id'])
    if len(entries) > 1:
        entries.sort()
        entries.insert(1, key)
        entries.insert(2, stitch['rank'])
        stitch2nmes[entries[0]] = entries[1:]
    return stitch2nmes

def PMEClashes(stitch2pmes, stitch):
    key = stitch['id']
    entries = []
    for node in stitch['sgroup']['members']:
        if node['source'] == 'Pharmaceutical Manufacturing Encyclopedia (Third Edition)':
            entries.append(node['name'])
    if len(entries) > 1:
        entries.sort()
        entries.insert(1, key)
        entries.insert(2, stitch['rank'])
        stitch2pmes[entries[0]] = entries[1:]
    return stitch2pmes

def activemoietyClashes(stitch2ams, stitch):
    key = stitch['id']
    entries = []
    for node in stitch['sgroup']['members']:
        if node['source'] == 'G-SRS, July 2018':
            if node['stitches'].has_key('T_ActiveMoiety'):
                if len(node['stitches']['T_ActiveMoiety']) > 1 and len(node['stitches']['T_ActiveMoiety'][0]) > 1:
                    #print key, node['stitches']['T_ActiveMoiety']
                    #sys.exit()
                    sys.stderr.write("ignoring multiple active moieties for GSRS entry\n")
                elif len(node['stitches']['T_ActiveMoiety']) > 1:
                    item = node['stitches']['T_ActiveMoiety']
                    node['stitches']['T_ActiveMoiety'] = [item]
                if node['stitches']['T_ActiveMoiety'][0] not in entries:
                    entries.append(node['stitches']['T_ActiveMoiety'][0])
    if len(entries) > 1:
        entries.sort()
        entries.insert(1, key)
        entries.insert(2, stitch['rank'])
    return stitch2ams

orphanList = ['Pharmaceutical Manufacturing Encyclopedia (Third Edition)', 'Broad Institute Drug List 2017-03-27', 'Rancho BioSciences, August 2018', 'DrugBank, July 2018', 'NCATS Pharmaceutical Collection, April 2012', 'Withdrawn and Shortage Drugs List Feb 2018']
def findOrphans(orphans, stitch):
    key = stitch['id']
    rank = stitch['rank']
    if rank == 1:
        node = stitch['sgroup']['members'][0]
        if node['source'] in orphanList:
            name = ''
            if not node.has_key('id'):
                name = node['name']
            else:
                name = node['id']
            orphans[name] = [node['source']]
    return orphans
        
def iterateStitches(funcs):
    dicts = []
    for item in funcs:
        dicts.append(dict())
    skip = 0
    top = 10
    max = 300000
    while skip < max:
        uri = site+'api/stitches/v1?top='+str(top)+'&skip='+str(skip)
        obj = requestJson(uri)
        if obj is None:
            skip = skip
        elif len(obj['contents']) == 0:
            skip = max
        else:
            for stitch in obj['contents']:
                for i in range(len(funcs)):
                    funcs[i](dicts[i], stitch)
        sys.stderr.write(uri+"\n")
        sys.stderr.flush()
        skip = skip + top
    return dicts

def getName(obj):
    name = ""
    for member in obj['sgroup']['members']:
        if name == "":
            if member.has_key('name'):
                name = member['name']
            elif member['stitches'].has_key('N_Name'):
                if len(member['stitches']['N_Name'][0]) == 1:
                    name = str(member['stitches']['N_Name'])
                else:
                    name = str(member['stitches']['N_Name'][0])
    if name == "":
        if obj['sgroup']['properties'].has_key('Synonyms'):
            if not isinstance(obj['sgroup']['properties']['Synonyms'], (list, tuple)):
                name = obj['sgroup']['properties']['Synonyms']['value']
            else:
                name = obj['sgroup']['properties']['Synonyms'][0]['value']
    if name == "":
        if obj['sgroup']['properties'].has_key('unii'):
            name = obj['sgroup']['properties']['unii'][0]['value']
    return name
            
def getEdges(nodes, edges = [], nodesrc = dict()):
    while len(nodes) > 0:
        newnodes = []
        for item in nodes:
            uri = site + 'api/node/' + str(item)
            obj = requestJson(uri)
            nodesrc[item] = obj['datasource']['name']
            if obj.has_key('neighbors'):
                for entry in obj['neighbors']:
                    edges.append([item, entry['id'], entry['reltype'], entry['value']])
                    if entry['id'] not in nodes and entry['id'] not in nodesrc.keys():
                        newnodes.append(entry['id'])
        nodes = newnodes
    return edges, nodesrc

def getPathsFromStitch(stitch):
    paths = []
    path = [stitch[0]]
    paths.append(path)
    for item in stitch[1:]:
        npath = list(path)
        npath.append(item)
        paths.append(npath)
    return paths

def extendPaths(paths, edges):
    npaths = []
    for path in paths:
        for edge in edges:
            if edge[0] == path[-1] and edge[1] not in path:
                npath = list(path)
                npath.append(edge[1])
                npaths.append(npath)
    return npaths

def probeUniiClash():
    uri = site + 'api/stitches/latest/ZY81Z83H0X'
    obj = requestJson(uri)
    stitch = []
    stitch.append(obj['sgroup']['parent'])
    for member in obj['sgroup']['members']:
        if member['node'] not in stitch:
            stitch.append(member['node'])

    edges = []
    sp = dict()
    nodes = []
    for item in stitch:
        uri = site + 'api/node/' + str(item)
        obj = requestJson(uri)
        sp[item] = obj['parent']
        nodes.append(sp[item])
        edges.append([item, sp[item], 'parent', 'parent'])

    edges, nodesrc = getEdges(nodes, edges)

    uri = site + 'api/stitches/latest/1222096'
    obj = requestJson(uri)
    ostitch = []
    ostitch.append(obj['sgroup']['parent'])
    for member in obj['sgroup']['members']:
        if member['node'] not in ostitch:
            ostitch.append(member['node'])

    nodes = []
    for item in ostitch:
        uri = site + 'api/node/' + str(item)
        obj = requestJson(uri)
        sp[item] = obj['parent']
        nodes.append(sp[item])
        edges.append([sp[item], item, 'rparent', 'rparent'])
    
    edges, nodesrc = getEdges(nodes, edges, nodesrc)

    print stitch
    print ostitch
    print nodesrc
    print edges

    paths = getPathsFromStitch(stitch)
    match = []
    round = 0
    while len(match) == 0:
        for p in paths:
            if p[-1] == ostitch[-1]:
                match.append(list(p))
        if len(match) == 0:
            round = round + 1
            paths = extendPaths(paths, edges)
            if len(paths) == 0 or round > 10:
                match = ["impossible"]
    print match


if __name__=="__main__":

    # list of tests to perform
    #nmeStitches: Multiple NME approvals (from ApprovalYears.txt) that belong to a single stitch
    #PMEClashes: Multiple PME entries that belong to a single stitch
    #activemoietyClashes: Multiple GSRS active moieties listed for a single stitch
    #uniiClashes: Different stitches (listed by id) that share a UNII - candidates for merge
    #findOrphans: Some resource entries were supposed to be stitched, but are orphaned
    #approvedStitches: Report on all the approved stitches from API
    
    tests = [nmeStitches, PMEClashes, activemoietyClashes, uniiClashes, findOrphans, approvedStitches]

    # initialize list of NMEs
    #nmeList = open("../data/approvalYears.txt", "r").readlines()
    nmeList = open('../data/FDA-NMEs-2018-08-07.txt', "r").readlines()
    for entry in nmeList[1:]:
        sline = entry.split('\t')
        if sline[3] not in NMEs:
            NMEs.append(sline[3])

    # initialize UNII preferred terms
    uniis = dict()
    fp = open('../temp/UNIIs-2018-09-07/UNII Names 31Aug2018.txt', 'r')
    line = fp.readline()
    line = fp.readline()
    while line != "":
        sline = line[0:-2].split("\t")
        uniis[sline[2]] = sline[3]
        line = fp.readline()
    fp.close()
    
    # iterate over stitches, perform tests
    outputs = iterateStitches(tests)

    # remove unimportant output for uniiClashes
    output = outputs[3]
    newoutput = dict()
    for key in output:
        if len(output[key]) > 2:
            newoutput[key] = output[key]
    outputs[3] = newoutput
            
    # write out results
    for i in range(len(tests)):
        test = tests[i].__name__
        output = outputs[i]
        keys = output.keys()
        keys.sort()
        for key in keys:
            keyname = ""
            if uniis.has_key(key):
                keyname = uniis[key]
            outline = test + "\t" + key.encode('ascii', 'ignore') + "\t" + keyname.encode('ascii', 'ignore')
            for item in output[key]:
                outline = outline + "\t" + str(item)
                if uniis.has_key(item):
                    outline = outline + "\t" + uniis[item]
            print outline  
    sys.exit()
    

    # TODO post-process unii clashes if desired
    for line in open("unii-clash.txt", "r").readlines():
        sline = line[0:-1].split("\t")
        unii = sline[0]
        nodes = sline[1:]

        if len(nodes) == 2:
            uri = site + 'api/stitches/latest/' + nodes[0]
            obj1 = requestJson(uri)
            uri = site + 'api/stitches/latest/' + nodes[1]
            obj2 = requestJson(uri)
            name1 = getName(obj1).encode('ascii', 'ignore')
            name2 = getName(obj2).encode('ascii', 'ignore')
            if obj1['rank'] == 1:
                print unii, nodes[0], name1, nodes[1], obj2['rank'], name2
            elif obj2['rank'] == 1:
                print unii, nodes[1], name2, nodes[0], obj1['rank'], name1
            sys.stdout.flush()
      
    
