"""Builds OrganiumProbe.CustomOrgan_Hauptwerk_xml from Hauptwerk's ExampleCustomOrgan1.

Every rank is pointed at the Polyphony Testing Organ samples that ship with Hauptwerk (package 1),
and given distinctive per-parameter values so that, after Hauptwerk compiles the custom organ, each
compacted tag in the compiled organ definition can be traced back to the parameter that produced it.

Value signatures (rank r = 0..6; value at pipe 36 / value at pipe 96, interpolated linearly between):
  Amp level dB              -3.17 - r   /  2.29 + r
  Amp wind depth dB          0.61 + r   / -0.73 - r
  Amp tremulant depth dB     0.83 + r   / -0.97 - r
  Pitch tuning % semitone    4.41 + r   / -4.53 - r
  Pitch wind depth %        61.0 + r    / 73.0 + r
  Pitch tremulant depth %   83.0 + r    / 97.0 + r
  Harmonic 3rd+ level dB    -1.37 - r   / -1.59 - r
  Layout code                r % 6 + 1
  Distance from listener m   1.25 + 0.5 r

Usage: python -I make_probe.py <ExampleCustomOrgan1 file> <output file>
"""
import re
import sys

RANK_VALUES = {
    'Amp_PipeMIDINoteNum036_LevelAdjustDecibels': lambda r: -3.17 - r,
    'Amp_PipeMIDINoteNum096_LevelAdjustDecibels': lambda r: 2.29 + r,
    'Amp_PipeMIDINoteNum036_WindModelModDepthAdjustDecibels': lambda r: 0.61 + r,
    'Amp_PipeMIDINoteNum096_WindModelModDepthAdjustDecibels': lambda r: -0.73 - r,
    'Amp_PipeMIDINoteNum036_TremulantModDepthAdjustDecibels': lambda r: 0.83 + r,
    'Amp_PipeMIDINoteNum096_TremulantModDepthAdjustDecibels': lambda r: -0.97 - r,
    'Pitch_PipeMIDINoteNum036_TuningAdjustPercentSemitones': lambda r: 4.41 + r,
    'Pitch_PipeMIDINoteNum096_TuningAdjustPercentSemitones': lambda r: -4.53 - r,
    'Pitch_PipeMIDINoteNum036_WindModelModDepthAdjustPercent': lambda r: 61.0 + r,
    'Pitch_PipeMIDINoteNum096_WindModelModDepthAdjustPercent': lambda r: 73.0 + r,
    'Pitch_PipeMIDINoteNum036_TremulantModDepthAdjustPercent': lambda r: 83.0 + r,
    'Pitch_PipeMIDINoteNum096_TremulantModDepthAdjustPercent': lambda r: 97.0 + r,
    'HarmonicShaping_PipeMIDINoteNum036_3rdAndUpperHarmLvlAdjDb': lambda r: -1.37 - r,
    'HarmonicShaping_PipeMIDINoteNum096_3rdAndUpperHarmLvlAdjDb': lambda r: -1.59 - r,
    'Position_LayoutOfPipesOnWindchestCode': lambda r: r % 6 + 1,
    'Position_RankDistanceFromListenerMetres': lambda r: 1.25 + 0.5 * r,
    # 0 (the example's value) makes Hauptwerk read each sample's exact pitch from the file. The
    # Polyphony WAVs don't carry one (their own organ definition states pitch method 3, harmonic 8),
    # and the probe was silent with 0. 8 = samples tuned to concert pitch at 8' (v3, 2026-10-31).
    'Samples_RankBasePitch64ftHarmNumIfAssumedTunedToConcertPitch': lambda r: 8,
    'Samples_InstallationPackageID': lambda r: 1,
    'Samples_MainSampleFileFolder': lambda r: 'PolyphonyTestingOrganRank',
    'Samples_RelSampleFileFolderIfRelSamplesStoredInSeparateFiles': lambda r: '',
    'Samples_ShortNoteReleaseSampleFileFolderIfAny': lambda r: '',
    'Samples_MaxNoteTimeMsForShortNoteReleaseSamplesIfAny': lambda r: 0,
    'Samples_MediumNoteReleaseSampleFileFolderIfAny': lambda r: '',
    'Samples_MaxNoteTimeMsForMediumNoteReleaseSamplesIfAny': lambda r: 0,
}

GENERAL_VALUES = {
    'UniqueOrganID': '800321',
    'Name': 'Organium Probe',
    'ShortName': 'OrganiumProbe',
    'OrganDefinitionFilenameExcludingExtension': 'OrganiumProbe',
}

# The wind model is an Advanced Edition feature. Without it, divisions generated with
# WindModel_EnableWindModel=Y feed their pipes from wind-chests with no static pressure, and the probe
# is silent (observed in the Free Edition, 2026-10-31). N gives the documented "constant 6" supply";
# the dependent attributes must then be blank.
DIVISION_VALUES = {
    'WindModel_EnableWindModel': 'N',
    'WindModel_TotalResrvrAndWndchstVolMetresCubed': '',
    'WindModel_AreaOfReservoirTableMetresSquared': '',
    'WindModel_ResrvrBellowsPositiveDampingCoeff': '',
    'WindModel_WindchestPressureDropPctAtMaxLoad': '',
}

PACKAGE = """<requiredinstallationpackage>
<InstallationPackageID>1</InstallationPackageID>
<Name>Hauptwerk Standard Components</Name>
<ShortName>HauptwerkStandardComponents</ShortName>
<PackageSupplierID>1</PackageSupplierID>
<SupplierName>Milan Digital Audio</SupplierName>
<MinimumPackageVersion>1</MinimumPackageVersion>
</requiredinstallationpackage>"""


def fmt(v):
    return v if isinstance(v, str) else (str(v) if isinstance(v, int) else f'{v:.2f}')


def set_field(block, name, value):
    pattern = re.compile(r'(<%s>)[^<]*(</%s>)' % (name, name))
    if not pattern.search(block):
        raise SystemExit(f'field {name} not found')
    return pattern.sub(lambda m: m.group(1) + fmt(value) + m.group(2), block, count=1)


def _apply(block, values):
    for name, value in values.items():
        block = set_field(block, name, value)
    return block


def main():
    src = open(sys.argv[1], encoding='utf-8').read()

    general = re.search(r'<_general>.*?</_general>', src, re.S)
    g = general.group(0)
    for name, value in GENERAL_VALUES.items():
        g = set_field(g, name, value)
    src = src[:general.start()] + g + src[general.end():]

    ranks = list(re.finditer(r'<rank>.*?</rank>', src, re.S))
    out, last = [], 0
    for r, m in enumerate(ranks):
        block = m.group(0)
        for name, value in RANK_VALUES.items():
            block = set_field(block, name, value(r))
        out.append(src[last:m.start()])
        out.append(block)
        last = m.end()
    out.append(src[last:])
    src = ''.join(out)

    src = re.sub(r'<division>.*?</division>',
                 lambda m: _apply(m.group(0), DIVISION_VALUES), src, flags=re.S)

    src = re.sub(r'<requiredinstallationpackage>.*?</requiredinstallationpackage>', PACKAGE, src, count=1, flags=re.S)
    if 'InstallationPackageID>10<' in src:
        raise SystemExit('a reference to package 10 remains')
    open(sys.argv[2], 'w', encoding='utf-8', newline='\r\n').write(src)
    print(f'wrote {sys.argv[2]} with {len(ranks)} ranks')


main()
