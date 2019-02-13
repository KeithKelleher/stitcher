#!/usr/bin/env python

import os
import sys
import cookielib
import urllib
import urllib2
import json
import time
import argparse
import pandas as pd
import base64
from tqdm import tqdm

# check that the python version is correct (need 2)
if sys.version_info[0] > 2:
    raise "Must be using Python 2! Aborting."

# check for arguments
args_p = argparse.ArgumentParser(description="Run Some Stitcher Tests")
args_p.add_argument('addr',
                    help="""a full Stitcher address OR
                            a shorthand: 'prod', 'dev', 'test' or 'local'""")
args_p.add_argument('--unii',
                    nargs="?",
                    default="../../temp/UNIIs-2018-09-07/UNII Names 31Aug2018.txt",
                    help="path to a file with unii names")

args_p.add_argument("--appyears",
                    nargs="?",
                    default="../../data/approvalYears.txt",
                    help="path to a file with unii names")

args_p.add_argument("--fdanme",
                    nargs="?",
                    default="../../data/FDA-NMEs-2018-08-07.txt",
                    help="path to a file with unii names")

args_p.add_argument('--maxsubs',
                    nargs="?",
                    type=int,
                    default=100000,
                    help="maximum number of substances to evaluate")

site_arg = args_p.parse_args().addr
unii_path = args_p.parse_args().unii
appyears_path = args_p.parse_args().appyears
fdanme_path = args_p.parse_args().fdanme
max_subs = args_p.parse_args().maxsubs

switcher = {
    "prod": "https://stitcher.ncats.io/",
    "dev": "https://stitcher-dev.ncats.io/",
    "test": "https://stitcher-test.ncats.io/",
    "local": "http://localhost:8080/"
    }

if site_arg in switcher:
    site = switcher[site_arg]
else:
    site = site_arg

date = time.strftime("%Y-%m-%d", time.gmtime())

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
    '''  defined in main
    header = ["UNII -- UNII PT"]
    '''

    for node in stitch['sgroup']['members']:
        if "stitches" in node and "I_UNII" in node["stitches"]:
            uniis = node['stitches']['I_UNII']

            if not isinstance(uniis, list):
                uniis = [uniis]

            for unii in uniis:
                unii2stitch.setdefault(unii,
                                       [" -- ".join([unii,
                                                     all_uniis.get(unii, "")])
                                        ])
                if stitch['id'] not in unii2stitch[unii]:
                    unii2stitch[unii].append(stitch['id'])

    return unii2stitch


def approvedStitches(approved, stitch):
    ''' defined in main
    header = ["UNII -- UNII PT",
              "Approval Year",
              "Stitch",
              "Rank"]
    '''

    if stitch['approved']:
        apprYear = "not given"

        if 'approvedYear' in stitch:
            apprYear = stitch['approvedYear']

        parent = stitch['sgroup']['parent']
        rank = stitch['rank']
        unii = ''

        for node in stitch['sgroup']['members']:
            if node['node'] == parent:
                if 'id' not in node:
                    unii = node['name']
                else:
                    unii = node['id']

        name = getName(stitch)

        approved[unii] = [" -- ".join([unii,
                                       all_uniis.get(unii, "")]),
                          apprYear,
                          parent,
                          rank]

    return approved


def nmeStitches(stitch2nmes, stitch, nmelist):
    ''' defined in main
    header = ["UNII -- UNII PT",  # can repeat
              "Stitch",
              "Rank"]
    '''

    key = stitch['id']
    entries = []

    for node in stitch['sgroup']['members']:
        if "g-srs" in node['source'].lower():
            if node['id'] in nmelist:
                # print node['id']
                # add the UNII and its preferred name to the list
                entries.append(" -- ".join([node['id'],
                                            all_uniis.get(node['id'], "")]))

    # print entries
    if len(entries) > 1:
        entries.sort()
        entries.insert(1, key)
        entries.insert(2, stitch['rank'])
        stitch2nmes[entries[0]] = entries

    return stitch2nmes


NMEs = []
NMEs2 = []


def nmeClashes(stitch2nmes, stitch):
    return nmeStitches(stitch2nmes, stitch, NMEs)


def nmeClashes2(stitch2nmes, stitch):
    return nmeStitches(stitch2nmes, stitch, NMEs2)


def PMEClashes(stitch2pmes, stitch):
    '''  defined in main
    header = ["PME Entry",
              "Stitch",
              "Rank"]
    '''

    key = stitch['id']
    entries = []

    for node in stitch['sgroup']['members']:
        if "manufacturing" in node['source'].lower():
            entries.append(node['name'])

    # if more than one PME entry found,
    # sort and add stitch info and rank after the first one
    if len(entries) > 1:
        entries.sort()
        entries.insert(1, key)
        entries.insert(2, stitch['rank'])
        stitch2pmes[entries[0]] = entries

    return stitch2pmes


def activemoietyClashes(stitch2ams, stitch):
    key = stitch['id']
    entries = []

    for node in stitch['sgroup']['members']:
        if "g-srs" in node['source'].lower():
            if 'T_ActiveMoiety' in node['stitches']:
                item = node['stitches']['T_ActiveMoiety']

                # check if item is a list -- if not, turn into a list
                if not isinstance(item, list):
                    item = [item]

                # if length of the t_activemoiety list is longer than 1, ignore
                if len(item) > 1:
                    sys.stderr.write("ignoring multiple active moieties"
                                     " for GSRS entry\n")

                # get a preferred name for the active moiety unii
                item = " -- ".join([item[0],
                                    all_uniis.get(item[0], "")])

                # if not already recoreded, record
                if item not in entries:
                    entries.append(item)

    if len(entries) > 1:
        entries.sort()
        entries.insert(1, key)
        entries.insert(2, stitch['rank'])
        stitch2ams[entries[0]] = entries

    return stitch2ams


orphanList = ['Pharmaceutical Manufacturing Encyclopedia (Third Edition)',
              'Broad Institute Drug List 2017-03-27',
              'Rancho BioSciences, August 2018',
              'DrugBank, July 2018',
              'NCATS Pharmaceutical Collection, April 2012',
              'Withdrawn and Shortage Drugs List Feb 2018']


def findOrphans(orphans, stitch):
    '''  defined in main
    header = ["Name / UNII / ID",
              "Blank / UNII PT",
              "Source"]
    '''

    key = stitch['id']
    rank = stitch['rank']

    if rank == 1:
        node = stitch['sgroup']['members'][0]

        if node['source'] in orphanList:
            name = ''
            id = ''
            status = ''

            if 'id' in node:
                id = node['id']
            else:
                id = node['name']
            if 'name' in node:
                name = node['name']

            if "broad" in node['source'].lower():
                if 'clinical_phase' in stitch['sgroup']['properties']:
                    status = '|' + stitch['sgroup']['properties']['clinical_phase']['value']

            if "drugbank" in node['source'].lower():
                if 'groups' in stitch['sgroup']['properties']:
                    status = ''

                    for group in stitch['sgroup']['properties']['groups']:
                        status = status + '|' + group['value']

            if "collection" in node['source'].lower():
                if 'DATASET' in stitch['sgroup']['properties']:
                    sets = []

                    for group in stitch['sgroup']['properties']['DATASET']:
                        sets.append(group['value'])
                    sets.sort()
                    status = '|'.join(sets)

                if 'name' in stitch['sgroup']['properties']:
                    name = stitch['sgroup']['properties']['name']['value']

            if "rancho" in node['source'].lower():
                if 'Conditions' in stitch['sgroup']['properties']:
                    status = '|has_conditions'

            item = node['source'] + status + "\t" + id
            entry = [id, all_uniis.get(id, ""), node['source'], status, name]
            orphans[item] = entry

    return orphans


def iterateStitches(funcs):
    dicts = [{} for d in range(len(funcs))]

    top = 10
    # max_subs = get_max_subs(100000, top)
    # max_subs = 100000

    for skip in tqdm(xrange(0, max_subs, top), ncols=100):
        uri = site+'api/stitches/v1?top='+str(top)+'&skip='+str(skip)
        obj = requestJson(uri)

        if 'contents' not in obj:
            obj = {'contents': obj}
            skip = max_subs

        if len(obj['contents']) == 0:
            skip = max_subs
        elif obj is not None:
            for stitch in obj['contents']:
                for i in range(len(funcs)):
                    funcs[i](dicts[i], stitch)

        # sys.stderr.write(uri+"\n")
        # sys.stderr.flush()

    return dicts


def get_site_obj(top, skip):
    
    uri = site + 'api/stitches/v1?top=' + str(top) + '&skip=' + str(skip)
    
    sys.stderr.write("Getting " + uri + "\n")
    sys.stderr.flush()

    obj = requestJson(uri)

    return obj

# TODO: possibly add a way to find the max number of pages
# helpful for a decent progress bar
def get_max_subs(startmax, top):
    precision = 1000

    # check the page using a start maximum

    "Trying to guess the max number of pages to navigate..."

    obj = get_site_obj(top, startmax)
    
    # if not contents object present, reduce page number
    # or the other way around
    if 'contents' not in obj or len(obj['contents']) == 0:
        newmax = startmax - precision
        sys.stderr.write(str(startmax) + " - too high!\n")
        sys.stderr.flush()
    else:
        newmax = startmax + precision
        sys.stderr.write(str(startmax) + " - too low!\n")
        sys.stderr.flush()

    if abs(newnewmax - startmax) > precision:
        # now if you call the same function 
        # and it returns the same number (startmax)

        startmax = get_max_subs(newmax, top)

    return newmax

def getName(obj):
    name = ""
    for member in obj["sgroup"]["members"]:
        if name == "":
            if "name" in member:
                name = member["name"]
            elif "N_Name" in member["stitches"]:
                if len(member["stitches"]["N_Name"][0]) == 1:
                    name = str(member["stitches"]["N_Name"])
                else:
                    name = str(member["stitches"]["N_Name"][0])
    if name == "":
        if "Synonyms" in obj["sgroup"]["properties"]:
            if not isinstance(obj["sgroup"]["properties"]["Synonyms"],
                              (list, tuple)):
                name = obj["sgroup"]["properties"]["Synonyms"]["value"]
            else:
                name = obj["sgroup"]["properties"]["Synonyms"][0]["value"]
    if name == "":
        if "unii" in obj["sgroup"]["properties"]:
            name = obj["sgroup"]["properties"]["unii"][0]["value"]
    return name


def getEdges(nodes, edges=[], nodesrc=dict()):
    while len(nodes) > 0:
        newnodes = []
        for item in nodes:
            uri = site + "api/node/" + str(item)
            obj = requestJson(uri)
            nodesrc[item] = obj["datasource"]["name"]
            if "neighbors" in obj:
                for entry in obj["neighbors"]:
                    edges.append([item, entry["id"],
                                  entry["reltype"],
                                  entry["value"]])
                    if entry["id"] not in nodes and entry["id"] not in nodesrc:
                        newnodes.append(entry["id"])
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


def get_uniis(unii_path):
    uniis = pd.read_csv(unii_path,
                        sep="\t")
    return (uniis[["UNII", "Display Name"]].drop_duplicates()
                                           .set_index("UNII")
                                           .to_dict()["Display Name"])


def output2df(output, test_name, header):
    # turn the dict with results into
    output_df = pd.DataFrame.from_dict(output,
                                       orient="index")

    if len(output_df.index) < 1:
        return output_df

    # add appropriate header
    n_extra_cols = output_df.shape[1] - len(header)

    if test_name == "uniiClashes":
        header += ["Stitch"]*n_extra_cols
    elif (test_name.startswith("nmeClashes")
          or test_name == "activemoietyClashes"):
        header += ["UNII -- UNII PT [OTHER]"]*n_extra_cols
    elif test_name == "PMEClashes":
        header += ["PME Entry [OTHER]"]*n_extra_cols
    else:
        header += ["UNKNOWN COLUMN"]*n_extra_cols

    output_df.columns = header

    # insert the test name
    output_df.insert(0, "Test", test_name)

    return output_df

def ranchoShouldBeApproved(rancho_drugs2check, stitch):
    '''  defined in main
    header = [UNII -- UNII PT]
    '''
    # if it's an approved drug...
    if stitch["USapproved"] != "null":
        # set a unii and gsrs name just in case
        # unii = "UNKNOWN"
        # gsrs_name = "UNKNOWN"

        # get the stitch id for debugging
        stitch_id = stitch["id"]

        rancho = {}
        gsrs = {}

        # have to be in the right order!
        phases = ["approved",
                  "withdrawn",
                  "phase iv",
                  "phase iii",
                  "phase ii",
                  "phase i",
                  "preclinical",
                  "basic research",
                  "natural metabolite",
                  "not provided",
                  "unknown",
                  "no highest phase"
                  ]

        # there can be multiple members per source
        for member in stitch["sgroup"]["members"]:
            # check if it has a node coming from rancho
            if "rancho" in member["source"].lower():
                rancho[member["payloadNode"]] = {"name": member["name"],
                                                 "highest_phase": []}

            # get gsrs uniis and names for all such members
            if "g-srs" in member["source"].lower():
                gsrs[member["id"]] = member.get("name", "MISSING")

        if len(rancho) == 0:
            return rancho_drugs2check

        if "Conditions" in stitch["sgroup"]["properties"]: 
            # iterate over conditions if available                
            # conditions are stored all together for all members;
            # so check condition belongs to current member
            for condition in stitch["sgroup"][
                                    "properties"][
                                    "Conditions"]:

                # condition node = rancho payload node id
                cond_node = condition["node"]

                # decode conditions 
                cond_obj = json.loads(base64
                                      .b64decode(condition["value"]))

                try:
                    highest_phase = cond_obj["HighestPhase"].lower()

                    if highest_phase == "approved":
                        return rancho_drugs2check

                    (rancho[cond_node]["highest_phase"]
                        .append(phases.index(highest_phase)))

                except ValueError:
                    # if highest phase is not present in the list
                    # add unknown
                    # (this should not happen)
                    (rancho[cond_node]["highest_phase"]
                        .append(phases.index("unknown")))

                except KeyError:
                    # if highest phase key does not exist
                    (rancho[cond_node]["highest_phase"]
                        .append(phases.index("no highest phase")))           

        for node_id in rancho:
            rancho[node_id]["highest_phase"].sort()

            try:
                rancho[node_id]["highest_phase"] = phases[rancho[node_id]["highest_phase"][0]]

            except IndexError:
                rancho[node_id]["highest_phase"] = "no conditions"

        # for each appropriate stitch, 
        # (approved drug, but not approved by rancho)
        # add gsrs and rancho data
        rancho_drugs2check[stitch_id] = [stitch_id,
                                         "\015".join(gsrs.keys()),
                                         "\015".join(gsrs.values()),
                                         "\015".join([x["name"] for x in rancho.values()]), 
                                         ("\015".join([x["highest_phase"] 
                                                      for x in rancho.values()])
                                                .upper())
                                        ]

    return rancho_drugs2check


if __name__ == "__main__":

    # list of tests to perform
    # nmeClashes and nmeClashes2 => run nmeStitches with different NME lists
    # from ApprovalYears.txt and FDA-NMEs-2018-08-07.txt, respectively
    # nmeStitches: Multiple NME approvals that belong to a single stitch
    # PMEClashes: Multiple PME entries that belong to a single stitch
    # activemoietyClashes: Multiple GSRS active moieties listed
    # for a single stitch
    # uniiClashes: Different stitches (listed by id)
    # that share a UNII - candidates for merge
    # findOrphans: Some resource entries were supposed to be stitched,
    # but are orphaned
    # approvedStitches: Report on all the approved stitches from API

    # tests = [nmeClashes,
    #          nmeClashes2,
    #          PMEClashes,
    #          activemoietyClashes,
    #          uniiClashes,
    #          findOrphans,
    #          approvedStitches,
    #          ranchoShouldBeApproved]

    tests = [ranchoShouldBeApproved]

    headers = {"nmeClashes": ["UNII -- UNII PT",
                              "Stitch",
                              "Rank"],
               "nmeClashes2": ["UNII -- UNII PT",
                               "Stitch",
                               "Rank"],
               "PMEClashes": ["PME Entry",
                              "Stitch",
                              "Rank"],
               "activemoietyClashes": ["UNII -- UNII PT",
                                       "Stitch",
                                       "Rank"],
               "uniiClashes": ["UNII -- UNII PT"],
               "findOrphans": ["Name / UNII / ID",
                               "Blank / UNII PT",
                               "Source",
                               "Status",
                               "Name"],
               "approvedStitches": ["UNII -- UNII PT",
                                    "Approval Year",
                                    "Stitch",
                                    "Rank"],
               "ranchoShouldBeApproved": ["Stitch ID",
                                          "UNIIs",
                                          "G-SRS Names",
                                          "Rancho Names",
                                          "Highest Phases"]
               }

    # initialize list of NMEs
    nmeList = open(appyears_path, "r").readlines()
    for entry in nmeList[1:]:
        sline = entry.split('\t')
        if sline[0] not in NMEs:
            NMEs.append(sline[0])

    nmeList2 = open(fdanme_path, "r").readlines()
    for entry in nmeList2[1:]:
        sline = entry.split('\t')
        if sline[3] not in NMEs2:
            NMEs2.append(sline[3])

    # initialize UNII preferred terms
    all_uniis = get_uniis(unii_path)

    # iterate over stitches, perform tests
    # returns a list of dictionaries
    outputs = iterateStitches(tests)

    # remove unimportant output for uniiClashes
    if uniiClashes in tests:
        outputs[tests.index(uniiClashes)] = {
                k: v for k,
                v in outputs[tests.index(uniiClashes)].items()
                if len(v) > 3
            }

    xl_writer = pd.ExcelWriter("".join([date,
                                        "_regression_",
                                        site_arg,
                                        ".xlsx"]))

    for test_index in range(len(tests)):
        test_name = tests[test_index].__name__

        '''
        print test_name
        print test_index
        print outputs[test_index]
        '''

        output_df = output2df(outputs[test_index],
                              test_name,
                              headers[test_name])

        output_df.to_excel(xl_writer,
                           sheet_name=test_name,
                           header=True,
                           index=False)

    xl_writer.save()

'''
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
'''
