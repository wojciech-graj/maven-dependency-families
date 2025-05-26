import matplotlib
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

S = 16

matplotlib.use("pgf")
matplotlib.rcParams.update({
    "pgf.texsystem": "pdflatex",
    'font.family': 'serif',
    'text.usetex': True,
    'pgf.rcfonts': False,
    'axes.labelsize': 'small',
    'axes.titlesize': 'medium',
    'scatter.marker': '.',
    'figure.dpi': 600,
})

df = pd.read_csv("../family_sizes.csv")

plt.figure(figsize=(3.4, 2.55))
plt.scatter(df['sz'], df['count'], color='black', s=S)
plt.yscale('log')
plt.xscale('log')
plt.xlabel("Family Cardinality")
plt.ylabel("Number of Families")
# plt.title("Frequency of Dependency Family Cardinalities")
plt.tight_layout()
plt.savefig('../plots/2_1_1.pgf')
plt.savefig('../plots/2_1_1.png')

df = pd.read_csv("../use_freq.csv")

grouped = df.groupby('position')['usage'].apply(
    lambda x: x.values).sort_index().to_numpy()

plt.figure(figsize=(3.4, 2.55))
violin_plot = plt.violinplot(grouped[1:6],
                             positions=range(2, 7),
                             showmeans=False,
                             showmedians=False,
                             showextrema=False)
for pc in violin_plot['bodies']:
    pc.set_facecolor('gray')
    pc.set_hatch('////////\\\\\\\\\\\\\\\\')
box_plot = plt.boxplot(grouped[1:6],
                       positions=range(2, 7),
                       showfliers=False,
                       patch_artist=True,
                       boxprops={'fill': None})
for median in box_plot['medians']:
    median.set_color('black')
plt.xlabel("Frequency Rank within Family")
plt.ylabel("Normalized Usage Frequency")
# plt.title("Normalized Usage Rate of Dependencies\nby Frequency Rank in Family",
#           wrap=True)
plt.tight_layout()
plt.savefig('../plots/2_2_1.pgf')
plt.savefig('../plots/2_2_1.png')

averages = np.array([np.mean(subarr) for subarr in grouped])

plt.figure(figsize=(3.4, 2.55))
plt.scatter(range(1, len(averages) + 1), averages, color='black', s=S)
plt.yscale('log')
plt.xscale('log')
plt.xlabel("Frequency Rank within Family")
plt.ylabel("Normalized Usage Frequency")
# plt.title("Normalized Usage Rate of Dependencies\nby Frequency Rank in Family",
#           wrap=True)
plt.tight_layout()
plt.savefig('../plots/2_2_2.pgf')
plt.savefig('../plots/2_2_2.png')

df = pd.read_csv("../co_use.csv")

plt.figure(figsize=(3.4, 2.55))
plt.scatter(df['num_deps'], df['cnt'], color='black', s=S)
plt.yscale('log')
plt.xscale('log')
plt.xlabel("Number of Dependencies from Family Used Together")
plt.ylabel("Total Occurrence Count")
# plt.title("Dependencies from the Same Family\nUsed Together")
plt.tight_layout()
plt.savefig('../plots/2_2_3.pgf')
plt.savefig('../plots/2_2_3.png')

df = pd.read_csv("../norm_co_use.csv")

plt.figure(figsize=(3.4, 2.55))
plt.hist(df['coeff'].values,
         bins=20,
         range=(0, 1),
         hatch='////',
         edgecolor='black',
         color='gray')
plt.xlabel("Proportion of Family Used Together")
plt.ylabel("Total Occurrence Count")
# plt.title("Proportion of Dependency Families\nUsed Together")
plt.tight_layout()
plt.savefig('../plots/2_2_4.pgf')
plt.savefig('../plots/2_2_4.png')

df = pd.read_csv("../consistency_scores.csv")

plt.figure(figsize=(3.4, 2.55))
plt.hist(df['score'].values,
         bins=20,
         range=(0, 1),
         hatch='////',
         edgecolor='black',
         color='gray')
plt.xlabel("Mean Homogeneity Score")
plt.ylabel("Number of Families")
# plt.title("Mean Homogeneity Score of\nDependency Families")
plt.tight_layout()
plt.savefig('../plots/2_3_1.pgf')
plt.savefig('../plots/2_3_1.png')

df = pd.read_csv("../release_size_diffs.csv")

values = df['size_diff'].values
plt.figure(figsize=(3.4, 2.55))
plt.hist(values[values < 32],
         bins=16,
         hatch='////',
         edgecolor='black',
         color='gray')
plt.xlabel("Absolute Difference in Source Size (B)")
plt.ylabel("Occurrence Count")
# plt.title("Absolute Difference in Source Size\nBetween Consecutive Releases")
plt.tight_layout()
plt.savefig('../plots/2_3_2.pgf')
plt.savefig('../plots/2_3_2.png')
