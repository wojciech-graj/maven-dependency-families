import matplotlib
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd

matplotlib.use("pgf")
matplotlib.rcParams.update({
    "pgf.texsystem": "pdflatex",
    'font.family': 'serif',
    'text.usetex': True,
    'pgf.rcfonts': False,
    'axes.labelsize': 'small',
    'axes.titlesize': 'medium',
})

df = pd.read_csv("../family_sizes.csv")

plt.figure(figsize=(3.4, 2.55))
plt.scatter(df['sz'], df['count'], marker='.')
plt.yscale('log')
plt.xscale('log')
plt.xlabel("Family Cardinality")
plt.ylabel("Count of Families")
plt.title("Frequency of Dependency Family Cardinalities")
plt.tight_layout()
plt.savefig('../2_1.pgf')

plt.figure(figsize=(3.4, 2.55))
plt.ecdf(df['sz'].values, weights=df['count'].values)
plt.xscale('log')
plt.xlabel("Family Cardinality")
plt.ylabel("Cumulative Proportion")
plt.title("Empirical CDF of\nDependency Family Cardinalities")
plt.tight_layout()
plt.savefig('../2_2.pgf')

df = pd.read_csv("../use_freq.csv")

grouped = df.groupby('position')['usage'].apply(
    lambda x: x.values).sort_index().to_numpy()

plt.figure(figsize=(3.4, 2.55))
plt.violinplot(grouped[1:6],
               positions=range(2, 7),
               showmeans=False,
               showmedians=False,
               showextrema=False)
plt.boxplot(grouped[1:6], positions=range(2, 7), showfliers=False)
plt.xlabel("Frequency Rank Within Family")
plt.ylabel("Normalized Usage Frequency")
plt.title("Usage Rate of Dependencies Across Families\nby Frequency Rank",
          wrap=True)
plt.tight_layout()
plt.savefig('../3_1.pgf')

averages = np.array([np.mean(subarr) for subarr in grouped])[:1000]

plt.figure(figsize=(3.4, 2.55))
plt.scatter(range(1, len(averages) + 1), averages, marker='.')
plt.yscale('log')
plt.xscale('log')
plt.xlabel("Frequency Rank")
plt.ylabel("Normalized Usage Frequency")
plt.title("Usage Rate of Dependencies Across Families\nby Frequency Rank",
          wrap=True)
plt.tight_layout()
plt.savefig('../3_2.pgf')

df = pd.read_csv("../co_use.csv")

plt.figure(figsize=(3.4, 2.55))
plt.scatter(df['num_deps'], df['cnt'], marker='.')
plt.yscale('log')
plt.xscale('log')
plt.xlabel("Count of Dependencies from Family Used Together")
plt.ylabel("Occurrence Count")
plt.title("Dependencies from the Same Family\nUsed Together")
plt.tight_layout()
plt.savefig('../4_1.pgf')
